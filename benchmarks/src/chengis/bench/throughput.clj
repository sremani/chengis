(ns chengis.bench.throughput
  "Benchmark 3: Concurrent Builds Throughput.
   Measures how build throughput scales with increasing concurrency."
  (:require [chengis.bench.stats :as stats]
            [chengis.bench.system :as bench-system]
            [chengis.bench.resource-monitor :as monitor]
            [chengis.dsl.core :as dsl]
            [chengis.engine.executor :as executor]
            [chengis.engine.workspace :as workspace]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors CountDownLatch TimeUnit]))

(defn- create-throughput-pipeline
  "Pipeline with a small simulated workload for throughput measurement."
  []
  (dsl/build-pipeline 'bench-throughput {}
    [(dsl/stage "Work"
       (dsl/step "Simulate" (dsl/sh "echo hello && sleep 0.1")))]))

(defn- run-concurrent-batch
  "Launch `concurrency` builds simultaneously, wait for all to finish.
   Returns {:wall-clock-ms :concurrency :builds-per-sec :individual [...]}"
  [system pipeline concurrency batch-num]
  (let [pool    (Executors/newFixedThreadPool concurrency)
        latch   (CountDownLatch. concurrency)
        results (atom [])
        wall-start (System/nanoTime)]
    (dotimes [i concurrency]
      (.submit pool
        ^Runnable
        (fn []
          (try
            (let [build-num (+ (* batch-num concurrency) (inc i))
                  start     (System/nanoTime)
                  result    (executor/run-build system pipeline
                              {:build-number build-num})
                  end       (System/nanoTime)]
              (when-let [ws (:workspace result)]
                (workspace/cleanup-workspace ws))
              (swap! results conj
                     {:thread-id i
                      :total-ms  (/ (- end start) 1e6)
                      :status    (:build-status result)}))
            (catch Exception e
              (swap! results conj
                     {:thread-id i
                      :total-ms  -1
                      :status    :error
                      :error     (.getMessage e)}))
            (finally
              (.countDown latch))))))
    (.await latch 300 TimeUnit/SECONDS)
    (.shutdown pool)
    (let [wall-end (System/nanoTime)
          wall-ms  (/ (- wall-end wall-start) 1e6)
          bps      (/ (double concurrency) (/ (- wall-end wall-start) 1e9))]
      {:wall-clock-ms  wall-ms
       :concurrency    concurrency
       :builds-per-sec bps
       :individual     @results})))

(defn run-throughput-benchmark
  "Run throughput benchmark across multiple concurrency levels.
   Returns {:levels [...] :resources {...} :metadata {...}}"
  [config]
  (let [{:keys [concurrency-levels builds-per-level warm-up]} (:throughput config)
        ws-root  (get-in config [:system :workspace-root])
        pipeline (create-throughput-pipeline)
        system   (bench-system/create-lightweight-system ws-root)
        monitor-state (monitor/start-monitor!
                        (get-in config [:resource-monitor :sample-interval-ms] 500))]

    ;; Warm-up
    (log/info (str "Throughput benchmark: warm-up (" warm-up " iterations)..."))
    (dotimes [i warm-up]
      (let [r (executor/run-build system pipeline {:build-number (inc i)})]
        (when-let [ws (:workspace r)]
          (workspace/cleanup-workspace ws))))

    ;; Run each concurrency level
    (log/info "Throughput benchmark: starting measured runs...")
    (let [level-results
          (mapv (fn [c]
                  (log/info (str "  Concurrency level: " c))
                  (let [batches-needed (max 1 (quot builds-per-level c))
                        batches (mapv (fn [b]
                                        (run-concurrent-batch system pipeline c b))
                                      (range batches-needed))
                        wall-times (map :wall-clock-ms batches)
                        bps-values (map :builds-per-sec batches)]
                    (log/info (str "    " (count batches) " batches, avg "
                                   (format "%.1f" (stats/mean bps-values))
                                   " builds/sec"))
                    {:concurrency     c
                     :batches-count   (count batches)
                     :wall-clock      (stats/summarize wall-times)
                     :builds-per-sec  (stats/summarize bps-values)
                     :avg-wall-ms     (stats/mean wall-times)
                     :avg-bps         (stats/mean bps-values)}))
                concurrency-levels)
          samples (monitor/stop-monitor! monitor-state)]

      {:levels     level-results
       :resources  {:summary       (monitor/resource-summary samples)
                    :samples-count (count samples)}
       :metadata   {:concurrency-levels concurrency-levels
                    :builds-per-level   builds-per-level}})))
