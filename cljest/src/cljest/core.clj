(ns cljest.core
  "Orchestrator — ties selector, mutator, runner, and reporter together
   into the main mutation testing pipeline."
  (:require [cljest.config :as config]
            [cljest.mutator :as mutator]
            [cljest.operators :as ops]
            [cljest.reporter :as reporter]
            [cljest.runner :as runner]
            [cljest.selector :as selector]
            [clojure.string :as str]
            [leiningen.core.main :as main]))

;; ---------------------------------------------------------------------------
;; Progress output
;; ---------------------------------------------------------------------------

(defn- progress
  "Print a progress message if verbose or always for key milestones."
  [verbose? & args]
  (when verbose?
    (apply main/info args)))

;; ---------------------------------------------------------------------------
;; Main pipeline
;; ---------------------------------------------------------------------------

(defn run-mutation-testing
  "Execute the full mutation testing pipeline.

   Arguments:
     project — Leiningen project map
     config  — resolved config map (from config/resolve-config)

   Returns report-data map with :results, :duration-ns, etc."
  [project config]
  (let [verbose? (:verbose config)
        dry-run? (:dry-run config)
        operator-ids (ops/resolve-preset (:operators config))
        start-time (System/nanoTime)]

    ;; Banner
    (main/info)
    (main/info "================================================================")
    (main/info "              CLJEST MUTATION TESTING")
    (main/info "================================================================")
    (main/info (format "  Operators:  %s (%d)"
                       (name (:operators config)) (count operator-ids)))
    (main/info (format "  Threshold:  %d%%" (:threshold config)))
    (main/info (format "  Timeout:    %dms" (:timeout config)))
    (when dry-run? (main/info "  Mode:       DRY RUN"))
    (main/info "================================================================")
    (main/info)

    ;; 1. Discover mutation targets
    (progress verbose? "Discovering namespaces...")
    (let [targets (selector/discover-mutation-targets config)]
      (if (empty? targets)
        (do
          (main/warn "No mutation targets found. Check your namespace filters.")
          {:results []
           :duration-ns (- (System/nanoTime) start-time)
           :source-ns-count 0
           :test-ns-count 0
           :config config})

        (do
          (main/info (format "  Found %d source namespace(s) with matching tests:"
                             (count targets)))
          (doseq [t targets]
            (main/info (format "    %s → %s"
                               (:source-ns t)
                               (str/join ", " (:test-namespaces t)))))
          (main/info)

          ;; 2. Find mutation sites and optionally run
          (let [all-results (atom [])
                total-mutations (atom 0)]
            (doseq [{:keys [source-ns source-file test-namespaces]} targets]
              (main/info (format "  Scanning %s ..." source-ns))
              (let [sites (mutator/find-mutation-sites source-file operator-ids)
                    mutations (mutator/expand-mutations sites)
                    mutation-count (count mutations)]
                (swap! total-mutations + mutation-count)
                (main/info (format "    %d mutation site(s), %d mutation(s)"
                                   (count sites) mutation-count))

                (when (and (seq mutations) (not dry-run?))
                  (main/info (format "    Running mutations for %s ..." source-ns))
                  (let [ns-results (runner/run-mutations-for-namespace
                                     project source-ns source-file
                                     test-namespaces mutations config)
                        ;; Tag results with source info
                        tagged (mapv #(assoc %
                                             :source-ns source-ns
                                             :source-file source-file)
                                     ns-results)
                        killed (count (filter #(#{:killed :timed-out} (:status %)) tagged))
                        survived (count (filter #(= :survived (:status %)) tagged))]
                    (main/info (format "    → %d killed, %d survived"
                                       killed survived))
                    (swap! all-results into tagged)))))

            ;; 3. Generate reports
            (let [duration-ns (- (System/nanoTime) start-time)
                  test-nses (into #{} (mapcat :test-namespaces targets))
                  report-data {:results @all-results
                               :duration-ns duration-ns
                               :source-ns-count (count targets)
                               :test-ns-count (count test-nses)
                               :config config}]

              (when dry-run?
                (main/info)
                (main/info (format "  DRY RUN: %d total mutations would be generated"
                                   @total-mutations))
                (main/info))

              (when (and (seq @all-results) (not dry-run?))
                ;; Text report
                (when (some #{:text} (:output-format config))
                  (reporter/text-report report-data))

                ;; HTML report
                (when (some #{:html} (:output-format config))
                  (let [path (reporter/html-report report-data
                                                    (:output-dir config))]
                    (main/info (format "  HTML report: %s" path)))))

              report-data)))))))

