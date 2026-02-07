(ns chengis.cli.commands
  "CLI command implementations."
  (:require [chengis.config :as config]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.db.secret-store :as secret-store]
            [chengis.dsl.core :as dsl]
            [chengis.dsl.chengisfile :as chengisfile]
            [chengis.engine.build-runner :as build-runner]
            [chengis.cli.output :as out]
            [clojure.java.io :as io]))

(defn- load-system
  "Bootstrap the system: load config, create datasource."
  []
  (let [cfg (config/load-config)
        db-path (get-in cfg [:database :path])
        ds (conn/create-datasource db-path)]
    {:config cfg
     :db ds
     :db-path db-path}))

;; --- init ---

(defn cmd-init
  "Initialize Chengis: run migrations and create workspace directory."
  [_args]
  (let [{:keys [config db-path]} (load-system)
        ws-root (get-in config [:workspace :root])]
    (migrate/migrate! db-path)
    (.mkdirs (io/file ws-root))
    (out/print-success (str "Chengis initialized. Database: " db-path ", Workspaces: " ws-root))))

;; --- job commands ---

(defn cmd-job-create
  "Create a job from a pipeline definition file."
  [args]
  (let [file-path (first args)]
    (if-not file-path
      (out/print-error "Usage: chengis job create <pipeline-file>")
      (let [{:keys [db]} (load-system)
            pipeline (dsl/load-pipeline-file file-path)
            _ (when-not pipeline
                (out/print-error "No pipeline definition found in file.")
                (System/exit 1))
            ;; Check if job already exists
            existing (job-store/get-job db (:pipeline-name pipeline))]
        (if existing
          (do
            (job-store/update-job! db (:pipeline-name pipeline) pipeline)
            (out/print-success (str "Updated job: " (:pipeline-name pipeline))))
          (do
            (job-store/create-job! db pipeline)
            (out/print-success (str "Created job: " (:pipeline-name pipeline)))))))))

(defn cmd-job-list
  "List all jobs."
  [_args]
  (let [{:keys [db]} (load-system)
        jobs (job-store/list-jobs db)]
    (if (empty? jobs)
      (println "No jobs found.")
      (do
        (out/print-header "Jobs")
        (out/print-table-row
          (format "%-30s" "NAME")
          (format "%-38s" "ID")
          "CREATED")
        (doseq [job jobs]
          (out/print-job job))))))

(defn cmd-job-show
  "Show job details."
  [args]
  (let [job-name (first args)]
    (if-not job-name
      (out/print-error "Usage: chengis job show <job-name>")
      (let [{:keys [db]} (load-system)
            job (job-store/get-job db job-name)]
        (if-not job
          (out/print-error (str "Job not found: " job-name))
          (out/print-job-detail job))))))

(defn cmd-job-delete
  "Delete a job."
  [args]
  (let [job-name (first args)]
    (if-not job-name
      (out/print-error "Usage: chengis job delete <job-name>")
      (let [{:keys [db]} (load-system)]
        (if (job-store/delete-job! db job-name)
          (out/print-success (str "Deleted job: " job-name))
          (out/print-error (str "Job not found: " job-name)))))))

(defn cmd-job-create-repo
  "Create a job from a git repository. Pipeline comes from Chengisfile in the repo."
  [args]
  (let [job-name (first args)
        git-url  (second args)]
    (if (or (nil? job-name) (nil? git-url))
      (out/print-error "Usage: chengis job create-repo <name> <git-url> [--branch <branch>]")
      (let [{:keys [db]} (load-system)
            ;; Parse optional --branch flag
            branch (let [idx (.indexOf (vec args) "--branch")]
                     (when (and (>= idx 0) (< (inc idx) (count args)))
                       (nth args (inc idx))))
            ;; Build a minimal pipeline — stages will come from Chengisfile at build time
            pipeline {:pipeline-name job-name
                      :description (str "Pipeline from repository (Chengisfile)")
                      :source {:type :git
                               :url git-url
                               :branch (or branch "main")
                               :depth 1}
                      :stages [{:stage-name "placeholder"
                                :parallel? false
                                :steps [{:step-name "waiting-for-chengisfile"
                                         :type :shell
                                         :command "echo 'No Chengisfile found in repository. Add a Chengisfile to your repo root.'"}]}]}
            existing (job-store/get-job db job-name)]
        (if existing
          (do (job-store/update-job! db job-name pipeline)
              (out/print-success (str "Updated repo job: " job-name " -> " git-url)))
          (do (job-store/create-job! db pipeline)
              (out/print-success (str "Created repo job: " job-name " -> " git-url))))))))

;; --- build commands ---

(defn cmd-build-trigger
  "Trigger a build for a job."
  [args]
  (let [job-name (first args)]
    (if-not job-name
      (out/print-error "Usage: chengis build trigger <job-name>")
      (let [system (load-system)
            {:keys [db]} system
            job (job-store/get-job db job-name)]
        (if-not job
          (out/print-error (str "Job not found: " job-name))
          (let [result (build-runner/execute-build! system job :manual)]
            (println)
            (println (str "Build #" (:build-number result) " completed: "
                          (name (:build-status result))))))))))

(defn cmd-build-list
  "List builds, optionally filtered by job."
  [args]
  (let [{:keys [db]} (load-system)
        job-name (first args)
        builds (if job-name
                 (let [job (job-store/get-job db job-name)]
                   (if job
                     (build-store/list-builds db (:id job))
                     (do (out/print-error (str "Job not found: " job-name)) nil)))
                 (build-store/list-builds db))]
    (when builds
      (if (empty? builds)
        (println "No builds found.")
        (do
          (out/print-header "Builds")
          (out/print-table-row
            (format "%-8s" "STATUS")
            (format "%-6s" "NUM")
            (format "%-38s" "ID")
            "TRIGGER"
            "STARTED")
          (doseq [build builds]
            (out/print-build build)))))))

(defn cmd-build-show
  "Show build details."
  [args]
  (let [build-id (first args)]
    (if-not build-id
      (out/print-error "Usage: chengis build show <build-id>")
      (let [{:keys [db]} (load-system)
            build (build-store/get-build db build-id)]
        (if-not build
          (out/print-error (str "Build not found: " build-id))
          (let [stages (build-store/get-build-stages db build-id)
                steps (build-store/get-build-steps db build-id)]
            (out/print-build-detail build stages steps)))))))

(defn cmd-build-log
  "Show build logs (step stdout/stderr)."
  [args]
  (let [build-id (first args)]
    (if-not build-id
      (out/print-error "Usage: chengis build log <build-id>")
      (let [{:keys [db]} (load-system)
            build (build-store/get-build db build-id)]
        (if-not build
          (out/print-error (str "Build not found: " build-id))
          (let [steps (build-store/get-build-steps db build-id)]
            (if (empty? steps)
              (println "No logs available for this build.")
              (out/print-build-log steps))))))))

(defn cmd-build-cancel
  "Cancel a running build."
  [args]
  (let [build-id (first args)]
    (if-not build-id
      (out/print-error "Usage: chengis build cancel <build-id>")
      (if (build-runner/cancel-build! build-id)
        (out/print-success (str "Build " build-id " cancelled."))
        (out/print-error (str "Build not found or not running: " build-id))))))

(defn cmd-build-retry
  "Retry a completed build."
  [args]
  (let [build-id (first args)]
    (if-not build-id
      (out/print-error "Usage: chengis build retry <build-id>")
      (let [system (load-system)
            {:keys [db]} system
            build (build-store/get-build db build-id)]
        (if-not build
          (out/print-error (str "Build not found: " build-id))
          (let [job (job-store/get-job-by-id db (:job-id build))
                result (build-runner/execute-build! system job :retry
                         {:parameters (:parameters build)})]
            (println)
            (println (str "Retry Build #" (:build-number result) " completed: "
                          (name (:build-status result))))))))))

;; --- pipeline commands ---

(defn cmd-pipeline-validate
  "Validate a pipeline definition file."
  [args]
  (let [file-path (first args)]
    (if-not file-path
      (out/print-error "Usage: chengis pipeline validate <file>")
      (try
        (let [pipeline (dsl/load-pipeline-file file-path)]
          (if pipeline
            (do
              (out/print-success (str "Pipeline '" (:pipeline-name pipeline) "' is valid."))
              (println "  Stages:" (count (:stages pipeline)))
              (doseq [stage (:stages pipeline)]
                (println "    -" (:stage-name stage)
                         (str "(" (count (:steps stage)) " steps)"
                              (when (:parallel? stage) " [parallel]")))))
            (out/print-error "No pipeline definition found in file.")))
        (catch Exception e
          (out/print-error (str "Validation failed: " (.getMessage e))))))))

(defn cmd-pipeline-validate-edn
  "Validate a Chengisfile (EDN format)."
  [args]
  (let [file-path (first args)]
    (if-not file-path
      (out/print-error "Usage: chengis pipeline validate-edn <file>")
      (try
        (let [result (chengisfile/parse-chengisfile file-path)]
          (if (:error result)
            (out/print-error (str "Invalid Chengisfile: " (:error result)))
            (let [p (:pipeline result)]
              (out/print-success "Chengisfile is valid.")
              (when (:description p)
                (println "  Description:" (:description p)))
              (println "  Stages:" (count (:stages p)))
              (doseq [stage (:stages p)]
                (println "    -" (:stage-name stage)
                         (str "(" (count (:steps stage)) " steps)"
                              (when (:parallel? stage) " [parallel]")))))))
        (catch Exception e
          (out/print-error (str "Failed to read file: " (.getMessage e))))))))

;; --- secret commands ---

(defn cmd-secret-set
  "Set a secret (global or job-scoped)."
  [args]
  (let [secret-name (first args)
        secret-value (second args)]
    (if (or (nil? secret-name) (nil? secret-value))
      (out/print-error "Usage: chengis secret set <name> <value> [--scope <job-id>]")
      (let [{:keys [config db]} (load-system)
            scope (let [idx (.indexOf (vec args) "--scope")]
                    (when (and (>= idx 0) (< (inc idx) (count args)))
                      (nth args (inc idx))))]
        (secret-store/set-secret! db config secret-name secret-value
                                  :scope (or scope "global"))
        (out/print-success (str "Secret '" secret-name "' set ("
                                (if scope (str "scope: " scope) "global") ")"))))))

(defn cmd-secret-list
  "List secret names."
  [args]
  (let [{:keys [db]} (load-system)
        scope (let [idx (.indexOf (vec args) "--scope")]
                (when (and (>= idx 0) (< (inc idx) (count args)))
                  (nth args (inc idx))))
        names (secret-store/list-secret-names db :scope (or scope "global"))]
    (if (empty? names)
      (println "No secrets found.")
      (do
        (out/print-header (str "Secrets (" (or scope "global") ")"))
        (doseq [n names]
          (println "  " n))))))

(defn cmd-secret-delete
  "Delete a secret."
  [args]
  (let [secret-name (first args)]
    (if-not secret-name
      (out/print-error "Usage: chengis secret delete <name> [--scope <job-id>]")
      (let [{:keys [db]} (load-system)
            scope (let [idx (.indexOf (vec args) "--scope")]
                    (when (and (>= idx 0) (< (inc idx) (count args)))
                      (nth args (inc idx))))]
        (if (secret-store/delete-secret! db secret-name :scope (or scope "global"))
          (out/print-success (str "Secret '" secret-name "' deleted."))
          (out/print-error (str "Secret not found: " secret-name)))))))

;; --- status ---

(defn cmd-status
  "Show system status."
  [_args]
  (let [{:keys [db]} (load-system)
        jobs (job-store/list-jobs db)
        all-builds (build-store/list-builds db)
        running (count (filter #(= :running (:status %)) all-builds))
        queued (count (filter #(= :queued (:status %)) all-builds))]
    (out/print-status running queued (count jobs) (count all-builds))))
