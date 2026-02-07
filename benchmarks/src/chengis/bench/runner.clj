(ns chengis.bench.runner
  "CLI entry point for the Chengis benchmark suite.
   Runs overhead, realistic, and/or throughput benchmarks."
  (:require [chengis.bench.config :as bench-config]
            [chengis.bench.overhead :as overhead]
            [chengis.bench.realistic :as realistic]
            [chengis.bench.throughput :as throughput]
            [chengis.bench.auth-overhead :as auth-overhead]
            [chengis.bench.report :as report]
            [chengis.bench.system :as bench-system]
            [clojure.tools.cli :refer [parse-opts]]
            [taoensso.timbre :as log])
  (:gen-class))

(def cli-options
  [["-b" "--benchmark NAME" "Benchmark to run: overhead, realistic, throughput, auth, all"
    :default "all"
    :validate [#(contains? #{"all" "overhead" "realistic" "throughput" "auth"} %)
               "Must be: all, overhead, realistic, throughput, or auth"]]
   ["-i" "--iterations N" "Override iteration count"
    :parse-fn #(Integer/parseInt %)]
   ["-o" "--output DIR" "Output directory"
    :default "benchmarks/results"]
   ["-f" "--format FMT" "Output format: edn, json, text, all"
    :default "all"]
   ["-h" "--help" "Show help"]])

(defn- collect-metadata
  "Collect system metadata for the results."
  []
  {:timestamp    (str (java.time.Instant/now))
   :chengis-version "0.2.0"
   :jvm-version  (System/getProperty "java.version")
   :jvm-vm       (System/getProperty "java.vm.name")
   :os           (str (System/getProperty "os.name") " "
                      (System/getProperty "os.version"))
   :cpu-cores    (.availableProcessors (Runtime/getRuntime))
   :max-memory-mb (/ (.maxMemory (Runtime/getRuntime)) 1048576.0)})

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (when (:help options)
      (println "Chengis Benchmark Suite")
      (println "=======================")
      (println summary)
      (System/exit 0))
    (when errors
      (doseq [e errors] (println "Error:" e))
      (System/exit 1))

    ;; Initialize plugins
    (bench-system/init-plugins!)

    ;; Build config with overrides
    (let [config (bench-config/merge-config
                   (cond-> {}
                     (:iterations options)
                     (-> (assoc-in [:overhead :iterations] (:iterations options))
                         (assoc-in [:realistic :iterations] (:iterations options)))
                     (:output options)
                     (assoc-in [:system :results-dir] (:output options))))
          benchmark (:benchmark options)
          start-time (System/nanoTime)]

      (println "\n================================================================")
      (println "          CHENGIS BENCHMARK SUITE")
      (println "================================================================")
      (println (str "  Benchmark: " benchmark))
      (println (str "  JVM:       " (System/getProperty "java.version")))
      (println (str "  CPU cores: " (.availableProcessors (Runtime/getRuntime))))
      (println (str "  Max heap:  " (format "%.0f MB" (/ (.maxMemory (Runtime/getRuntime)) 1048576.0))))
      (println "================================================================\n")

      (let [results
            (cond-> {:metadata (collect-metadata)}

              (contains? #{"all" "overhead"} benchmark)
              (assoc :overhead
                (do (log/info "=== Starting overhead benchmark ===")
                    (overhead/run-overhead-benchmark config)))

              (contains? #{"all" "realistic"} benchmark)
              (assoc :realistic
                (do (log/info "=== Starting realistic benchmark ===")
                    (realistic/run-realistic-benchmark config)))

              (contains? #{"all" "throughput"} benchmark)
              (assoc :throughput
                (do (log/info "=== Starting throughput benchmark ===")
                    (throughput/run-throughput-benchmark config)))

              (contains? #{"all" "auth"} benchmark)
              (assoc :auth-overhead
                (do (log/info "=== Starting auth overhead benchmark ===")
                    (auth-overhead/run-auth-overhead-benchmark config))))]

        ;; Print summary
        (report/print-summary results)

        ;; Write results
        (report/write-results! results config)

        (let [elapsed (/ (- (System/nanoTime) start-time) 1e9)]
          (println (format "\nBenchmark suite completed in %.1f seconds." elapsed)))))))
