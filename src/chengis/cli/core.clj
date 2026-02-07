(ns chengis.cli.core
  "CLI dispatcher for Chengis."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [chengis.cli.commands :as cmd]))

(def cli-options
  [["-h" "--help" "Show help"]
   ["-v" "--version" "Show version"]])

(def version "0.1.0-SNAPSHOT")

(defn usage []
  (println "
Chengis — CI/CD Engine

Usage: chengis <command> [args...]

Commands:
  init                     Initialize Chengis (create DB and workspace)
  job create <file>        Create/update a job from a pipeline file
  job create-repo <name> <url>  Create job from repo with Chengisfile
  job list                 List all jobs
  job show <name>          Show job details
  job delete <name>        Delete a job
  build trigger <job>      Trigger a build for a job
  build cancel <build-id>  Cancel a running build
  build retry <build-id>   Retry a completed build
  build list [job]         List builds (optionally by job)
  build show <build-id>    Show build details
  build log <build-id>     Show build step output
  pipeline validate <file> Validate a pipeline file (.clj)
  pipeline validate-edn <file>  Validate a Chengisfile (EDN)
  status                   Show system status
  serve                    Start the web UI (http://localhost:8080)

Options:
  -h, --help               Show this help
  -v, --version            Show version"))

(defn dispatch
  "Parse command-line arguments and dispatch to the appropriate handler."
  [args]
  (let [{:keys [options arguments]} (parse-opts args cli-options :in-order true)]
    (cond
      (:help options)    (usage)
      (:version options) (println (str "Chengis " version))
      :else
      (let [command (first arguments)
            sub-command (second arguments)
            rest-args (if sub-command
                        (drop 2 arguments)
                        (rest arguments))]
        (case command
          "init"     (cmd/cmd-init rest-args)
          "job"      (case sub-command
                       "create"      (cmd/cmd-job-create rest-args)
                       "create-repo" (cmd/cmd-job-create-repo rest-args)
                       "list"        (cmd/cmd-job-list rest-args)
                       "show"        (cmd/cmd-job-show rest-args)
                       "delete"      (cmd/cmd-job-delete rest-args)
                       (do (println (str "Unknown job command: " sub-command))
                           (usage)))
          "build"    (case sub-command
                       "trigger" (cmd/cmd-build-trigger rest-args)
                       "cancel"  (cmd/cmd-build-cancel rest-args)
                       "retry"   (cmd/cmd-build-retry rest-args)
                       "list"    (cmd/cmd-build-list rest-args)
                       "show"    (cmd/cmd-build-show rest-args)
                       "log"     (cmd/cmd-build-log rest-args)
                       (do (println (str "Unknown build command: " sub-command))
                           (usage)))
          "pipeline" (case sub-command
                       "validate"     (cmd/cmd-pipeline-validate rest-args)
                       "validate-edn" (cmd/cmd-pipeline-validate-edn rest-args)
                       (do (println (str "Unknown pipeline command: " sub-command))
                           (usage)))
          "status"   (cmd/cmd-status rest-args)
          "serve"    (do (println "Starting web server...")
                         ((requiring-resolve 'chengis.web.server/start!)))
          nil        (usage)
          (do (println (str "Unknown command: " command))
              (usage)))))))
