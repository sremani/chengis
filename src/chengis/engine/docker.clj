(ns chengis.engine.docker
  "Docker container utilities for building docker run commands,
   pulling images, and resolving volume mounts."
  (:require [chengis.engine.process :as process]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Volume resolution
;; ---------------------------------------------------------------------------

(defn resolve-volumes
  "Resolve volume mount specifications.
   The special token ':workspace' is replaced with the actual workspace path.
   Input: workspace-dir, vector of volume specs like [\"src:/app/src\" \":workspace:/workspace\"]
   Output: vector of resolved volume strings."
  [workspace-dir volumes]
  (when (seq volumes)
    (mapv (fn [vol]
            (str/replace vol ":workspace" workspace-dir))
          volumes)))

;; ---------------------------------------------------------------------------
;; Environment variable formatting
;; ---------------------------------------------------------------------------

(defn- env-flags
  "Convert an env map to docker -e flags."
  [env-map]
  (when (seq env-map)
    (mapcat (fn [[k v]]
              ["-e" (str k "=" v)])
            env-map)))

(defn- volume-flags
  "Convert volume specs to docker -v flags."
  [volumes]
  (when (seq volumes)
    (mapcat (fn [vol] ["-v" vol]) volumes)))

;; ---------------------------------------------------------------------------
;; Docker command generation
;; ---------------------------------------------------------------------------

(defn build-docker-run-cmd
  "Build a 'docker run' command string from a step definition and build context.

   Step-def keys:
     :image      - Docker image name (required)
     :command    - Command to run inside the container (required)
     :env        - Additional environment variables map
     :volumes    - Volume mount specifications
     :workdir    - Working directory inside the container (default: /workspace)
     :network    - Docker network mode
     :docker-args - Additional raw docker arguments

   Build-ctx keys:
     :workspace  - Host workspace directory
     :env        - Build environment variables"
  [step-def build-ctx]
  (let [image (:image step-def)
        command (:command step-def)
        workspace (:workspace build-ctx)
        workdir (or (:workdir step-def) "/workspace")
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
                ["-w" workdir]
                (env-flags all-env)
                (when-let [net (:network step-def)] ["--network" net])
                (or (:docker-args step-def) [])
                [image "sh" "-c" (pr-str command)])]
    (str/join " " parts)))

(defn build-docker-compose-cmd
  "Build a 'docker-compose run' command string.

   Step-def keys:
     :compose-file - Path to docker-compose file
     :service      - Service name to run
     :command      - Command to execute in the service"
  [step-def build-ctx]
  (let [compose-file (or (:compose-file step-def) "docker-compose.yml")
        service (:service step-def)
        command (:command step-def)
        workspace (:workspace build-ctx)]
    (str "cd " workspace " && docker-compose -f " compose-file
         " run --rm " service " sh -c " (pr-str command))))

;; ---------------------------------------------------------------------------
;; Image management
;; ---------------------------------------------------------------------------

(defn pull-image!
  "Pull a Docker image. Returns the process result."
  [image & {:keys [timeout] :or {timeout 300000}}]
  (log/info "Pulling Docker image:" image)
  (process/execute-command
    {:command (str "docker pull " image)
     :timeout timeout}))

(defn image-exists?
  "Check if a Docker image exists locally."
  [image]
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
