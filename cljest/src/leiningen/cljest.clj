(ns leiningen.cljest
  "Leiningen plugin entry point for cljest mutation testing.

   Usage:
     lein cljest                           Run with defaults
     lein cljest --namespaces \"engine.*\"  Filter source namespaces
     lein cljest --threshold 85            Set minimum score
     lein cljest --operators fast          Use fast operator preset
     lein cljest --dry-run                 Show mutation count only
     lein cljest --help                    Show help"
  (:require [cljest.config :as config]
            [cljest.core :as core]
            [cljest.reporter :as reporter]
            [leiningen.core.main :as main]))

(defn cljest
  "Run mutation testing on the project."
  [project & args]
  (let [cfg (config/resolve-config project args)]
    ;; Show help if requested
    (when (:help cfg)
      (println (config/help-text))
      (main/exit 0))

    ;; Run mutation testing
    (let [report-data (core/run-mutation-testing project cfg)
          results (:results report-data)
          score (reporter/mutation-score results)
          threshold (:threshold cfg 80)]

      ;; Exit with non-zero if below threshold (and not dry-run)
      (when (and (not (:dry-run cfg))
                 (seq results)
                 (< score threshold))
        (main/exit 1)))))
