(ns chengis.engine.docker
  "Docker container utilities for building docker run commands,
   pulling images, and resolving volume mounts."
  (:require [chengis.engine.process :as process]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Input validation
;; ---------------------------------------------------------------------------

(def ^:private safe-image-pattern
  "Pattern for valid Docker image names (registry/name:tag format)."
  #"^[a-zA-Z0-9][a-zA-Z0-9._\-/:@]*$")

(def ^:private safe-name-pattern
  "Pattern for safe Docker names (compose service, network, etc.)."
  #"^[a-zA-Z0-9][a-zA-Z0-9._\-]*$")

(defn- validate-image-name!
  "Validate a Docker image name. Throws on invalid input."
  [image]
  (when (or (str/blank? image)
            (not (re-matches safe-image-pattern image))
            (> (count image) 256))
    (throw (ex-info "Invalid Docker image name" {:image image}))))

(defn- validate-name!
  "Validate a Docker name (service, network, etc.). Throws on invalid input."
  [value label]
  (when (and value
             (or (str/blank? value)
                 (not (re-matches safe-name-pattern value))
                 (> (count value) 128)))
    (throw (ex-info (str "Invalid Docker " label) {label value}))))

(defn- shell-quote
  "Shell-quote a string value for safe embedding in commands."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

;; ---------------------------------------------------------------------------
;; Volume resolution
;; ---------------------------------------------------------------------------

(defn resolve-volumes
  "Resolve volume mount specifications.
   The token '${WORKSPACE}' at the start of a path component is replaced
   with the actual workspace path.
   Input: workspace-dir, vector of volume specs like [\"src:/app/src\" \"${WORKSPACE}:/workspace\"]
   Output: vector of resolved volume strings."
  [workspace-dir volumes]
  (when (seq volumes)
    (mapv (fn [vol]
            (-> vol
                (str/replace "${WORKSPACE}" workspace-dir)
                ;; Legacy support: also replace :workspace at start of string
                (str/replace-first #"^:workspace" workspace-dir)))
          volumes)))

;; ---------------------------------------------------------------------------
;; Environment variable formatting
;; ---------------------------------------------------------------------------

(defn- env-flags
  "Convert an env map to docker -e flags. Values are shell-quoted for safety."
  [env-map]
  (when (seq env-map)
    (mapcat (fn [[k v]]
              ["-e" (str k "=" (shell-quote v))])
            env-map)))

(defn- volume-flags
  "Convert volume specs to docker -v flags."
  [volumes]
  (when (seq volumes)
    (mapcat (fn [vol] ["-v" (shell-quote vol)]) volumes)))

;; ---------------------------------------------------------------------------
;; Docker command generation
;; ---------------------------------------------------------------------------

(def ^:private safe-cache-volume-pattern
  "Pattern for valid Docker named volume names (alphanumeric, hyphens, underscores)."
  #"^[a-zA-Z0-9][a-zA-Z0-9_\-]*$")

(def ^:private safe-mount-path-pattern
  "Pattern for valid container mount paths (absolute paths with common chars)."
  #"^/[a-zA-Z0-9._/\-]+$")

(defn- validate-cache-volume-name!
  "Validate a cache volume name. Throws on invalid input."
  [vol-name]
  (when (or (str/blank? (str vol-name))
            (not (re-matches safe-cache-volume-pattern (str vol-name)))
            (> (count (str vol-name)) 128))
    (throw (ex-info "Invalid cache volume name"
                    {:volume-name vol-name
                     :pattern (str safe-cache-volume-pattern)}))))

(defn- validate-mount-path!
  "Validate a container mount path. Must be an absolute path with safe characters."
  [mount-path]
  (let [path-str (str mount-path)]
    (when (or (str/blank? path-str)
              (not (re-matches safe-mount-path-pattern path-str))
              (> (count path-str) 256)
              ;; Reject path traversal attempts
              (str/includes? path-str ".."))
      (throw (ex-info "Invalid cache volume mount path"
                      {:mount-path mount-path
                       :pattern (str safe-mount-path-pattern)})))))

(defn- cache-volume-flags
  "Convert cache volumes map to docker -v flags with named volumes.
   Cache volumes use Docker named volumes (not bind mounts).
   Validates both volume name and mount path for security.
   Format: {\"vol-name\" \"/mount/path\"} → [\"-v\" \"vol-name:/mount/path\"]"
  [cache-volumes]
  (when (seq cache-volumes)
    (mapcat (fn [[vol-name mount-path]]
              (let [vname (name vol-name)]
                (validate-cache-volume-name! vname)
                (validate-mount-path! mount-path)
                ["-v" (str vname ":" (shell-quote (str mount-path)))]))
            cache-volumes)))

(defn build-docker-run-cmd
  "Build a 'docker run' command string from a step definition and build context.

   Step-def keys:
     :image         - Docker image name (required, validated)
     :command       - Command to run inside the container (required)
     :env           - Additional environment variables map
     :volumes       - Volume mount specifications
     :cache-volumes - Named Docker volumes for persistent caching {name mount-path}
     :workdir       - Working directory inside the container (default: /workspace)
     :network       - Docker network mode (validated)
     :docker-args   - Additional docker arguments (validated, only flags allowed)

   Build-ctx keys:
     :workspace  - Host workspace directory
     :env        - Build environment variables"
  [step-def build-ctx]
  (let [image (:image step-def)
        command (:command step-def)
        workspace (:workspace build-ctx)
        workdir (or (:workdir step-def) "/workspace")
        ;; Validate inputs
        _ (validate-image-name! image)
        _ (when (:network step-def) (validate-name! (:network step-def) "network"))
        ;; Validate docker-args: only allow flags starting with -
        docker-args (when-let [args (:docker-args step-def)]
                      (let [valid-args (filterv #(str/starts-with? % "-") args)]
                        (when (not= (count valid-args) (count args))
                          (log/warn "Stripped non-flag docker-args:" (remove #(str/starts-with? % "-") args)))
                        valid-args))
        ;; Merge build-level and step-level env
        all-env (merge (:env build-ctx) (:env step-def))
        ;; Resolve volumes: always mount workspace, plus any extras
        workspace-vol (str workspace ":" workdir)
        extra-vols (resolve-volumes workspace (:volumes step-def))
        all-vols (into [workspace-vol] (or extra-vols []))
        ;; Build the docker command
        parts (concat
                ["docker" "run" "--rm"]
                (volume-flags all-vols)
                (cache-volume-flags (:cache-volumes step-def))
                ["-w" (shell-quote workdir)]
                (env-flags all-env)
                (when-let [net (:network step-def)] ["--network" net])
                (or docker-args [])
                [image "sh" "-c" (shell-quote command)])]
    (str/join " " parts)))

(defn build-docker-compose-cmd
  "Build a 'docker-compose run' command string.

   Step-def keys:
     :compose-file - Path to docker-compose file (validated)
     :service      - Service name to run (validated)
     :command      - Command to execute in the service"
  [step-def build-ctx]
  (let [compose-file (or (:compose-file step-def) "docker-compose.yml")
        service (:service step-def)
        command (:command step-def)
        workspace (:workspace build-ctx)]
    ;; Validate inputs
    (validate-name! service "service")
    (str "cd " (shell-quote workspace)
         " && docker-compose -f " (shell-quote compose-file)
         " run --rm " service " sh -c " (shell-quote command))))

;; ---------------------------------------------------------------------------
;; Image management
;; ---------------------------------------------------------------------------

(defn pull-image!
  "Pull a Docker image. Returns the process result."
  [image & {:keys [timeout] :or {timeout 300000}}]
  (validate-image-name! image)
  (log/info "Pulling Docker image:" image)
  (process/execute-command
    {:command (str "docker pull " image)
     :timeout timeout}))

(defn image-exists?
  "Check if a Docker image exists locally."
  [image]
  (validate-image-name! image)
  (let [result (process/execute-command
                 {:command (str "docker image inspect " image " > /dev/null 2>&1")
                  :timeout 10000})]
    (zero? (:exit-code result))))

(defn ensure-image!
  "Ensure a Docker image is available locally.
   Pull policy: :always, :if-not-present, :never"
  [image pull-policy]
  (case pull-policy
    :always (pull-image! image)
    :never nil
    ;; :if-not-present (default)
    (when-not (image-exists? image)
      (pull-image! image))))
