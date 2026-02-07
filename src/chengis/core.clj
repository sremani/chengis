(ns chengis.core
  (:require [chengis.cli.core :as cli])
  (:gen-class))

(defn -main
  "Chengis entry point.
   'serve' — start web UI (master mode)
   'agent' — start as a distributed build agent
   otherwise — CLI commands"
  [& args]
  (case (first args)
    "serve"
    (do
      ;; Require web.server dynamically to avoid loading http-kit for CLI usage
      (require 'chengis.web.server)
      ((resolve 'chengis.web.server/start!) (rest args)))

    "agent"
    (do
      ;; Require agent.core dynamically
      (require 'chengis.agent.core)
      (let [agent-args (rest args)
            ;; Simple arg parsing for agent mode
            opts (reduce (fn [m [k v]]
                           (case k
                             "--master-url" (assoc m :master-url v)
                             "--port"       (assoc m :port (Integer/parseInt v))
                             "--labels"     (assoc m :labels (set (clojure.string/split v #",")))
                             "--max-builds" (assoc m :max-builds (Integer/parseInt v))
                             "--auth-token" (assoc m :auth-token v)
                             "--name"       (assoc m :name v)
                             m))
                         {:master-url "http://localhost:8080"
                          :port 9090}
                         (partition 2 agent-args))]
        ((resolve 'chengis.agent.core/start-agent!) opts)
        ;; Keep the process alive
        @(promise)))

    ;; Default: CLI commands
    (cli/dispatch args)))
