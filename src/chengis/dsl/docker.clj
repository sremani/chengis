(ns chengis.dsl.docker
  "Docker DSL helpers for defining containerized pipeline steps.

   Usage:
     ;; Per-step Docker container
     (stage \"Build\"
       (docker-step \"maven:3.9\" \"Unit Tests\" \"mvn test\"))

     ;; Stage-level container (all steps share the image)
     (container {:image \"node:18\"}
       (stage \"Build\"
         (step \"Install\" (sh \"npm install\"))
         (step \"Test\" (sh \"npm test\"))))

     ;; Docker Compose step
     (stage \"Integration\"
       (docker-compose-step \"api\" \"Integration Tests\"
         \"pytest tests/integration/\"
         :compose-file \"docker-compose.test.yml\"))"
  (:require [chengis.dsl.core :as dsl]))

;; ---------------------------------------------------------------------------
;; Docker step helpers
;; ---------------------------------------------------------------------------

(defn docker-step
  "Define a step that runs inside a Docker container.
   Options:
     :env        - map of environment variables
     :volumes    - vector of volume mount specs
     :workdir    - working directory inside the container (default: /workspace)
     :network    - Docker network mode
     :timeout    - timeout in milliseconds
     :pull-policy - :always, :if-not-present, :never
     :docker-args - additional raw docker arguments"
  [image step-name command & {:keys [env volumes workdir network timeout
                                      pull-policy docker-args]}]
  (cond-> {:type :docker
           :step-name step-name
           :image image
           :command command}
    env         (assoc :env env)
    volumes     (assoc :volumes volumes)
    workdir     (assoc :workdir workdir)
    network     (assoc :network network)
    timeout     (assoc :timeout timeout)
    pull-policy (assoc :pull-policy pull-policy)
    docker-args (assoc :docker-args docker-args)))

(defn docker-compose-step
  "Define a step that runs inside a Docker Compose service.
   Options:
     :compose-file - path to docker-compose file (default: docker-compose.yml)
     :env          - map of environment variables
     :timeout      - timeout in milliseconds"
  [service step-name command & {:keys [compose-file env timeout]}]
  (cond-> {:type :docker-compose
           :step-name step-name
           :service service
           :command command}
    compose-file (assoc :compose-file compose-file)
    env          (assoc :env env)
    timeout      (assoc :timeout timeout)))

;; ---------------------------------------------------------------------------
;; Container wrapping (stage-level)
;; ---------------------------------------------------------------------------

(defn container
  "Wrap stages/steps to run inside a shared container image.
   Injects :container configuration into all child stages.

   container-opts keys:
     :image       - Docker image name (required)
     :volumes     - volume mount specs
     :workdir     - working directory (default: /workspace)
     :network     - Docker network mode
     :pull-policy - :always, :if-not-present, :never
     :docker-args - additional raw docker arguments

   Usage:
     (container {:image \"node:18\"}
       (stage \"Build\"
         (step \"Install\" (sh \"npm install\"))))"
  [container-opts & stages]
  (mapv (fn [s]
          (if (:stage-name s)
            ;; It's a stage — inject container config
            (assoc s :container container-opts)
            ;; Could be a post-actions or other map, pass through
            s))
        stages))
