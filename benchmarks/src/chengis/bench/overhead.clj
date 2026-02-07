(ns chengis.bench.overhead
  "Benchmark 1: Echo Overhead.
   Measures pure CI/CD framework overhead by running 'echo hello' N times.
   Two modes: executor-only (no DB) and full-lifecycle (with DB)."
  (:require [chengis.bench.stats :as stats]
            [chengis.bench.system :as bench-system]
            [chengis.bench.resource-monitor :as monitor]
            [chengis.dsl.core :as dsl]
            [chengis.engine.executor :as executor]
            [chengis.engine.build-runner :as build-runner]
            [chengis.engine.workspace :as workspace]
            [taoensso.timbre :as log]))

(defn- create-echo-pipeline
  "Create a minimal echo pipeline for overhead measurement.
   Uses build-pipeline (fn form) to avoid polluting the global registry."
  []
  (dsl/build-pipeline 'bench-echo {}
    [(dsl/stage "Run" (dsl/step "Echo" (dsl/sh "echo hello")))]))

(defn- extract-step-ms
  "Extract the actual command duration-ms from the first step of the first stage."
  [result]
  (or (-> result :stage-results first :step-results first :duration-ms) 0))

(defn- run-single-executor-only
  "Execute one echo build via executor (no DB). Returns timing map."
  [system pipeline iteration]
  (let [params {:build-number iteration}
        start  (System/nanoTime)
        result (executor/run-build system pipeline params)
        end    (System/nanoTime)
        total  (/ (- end start) 1e6)
        step   (extract-step-ms result)]
    (when-let [ws (:workspace result)]
      (workspace/cleanup-workspace ws))
    {:iteration    iteration
     :total-ms     total
     :step-ms      (double step)
     :overhead-ms  (- total (double step))
     :build-status (:build-status result)}))

(defn- run-single-full-lifecycle
  "Execute one echo build via build-runner (with DB). Returns timing map."
  [system job iteration]
  (let [start  (System/nanoTime)
        result (build-runner/execute-build! system job :manual)
        end    (System/nanoTime)
        total  (/ (- end start) 1e6)
        step   (extract-step-ms result)]
    (when-let [ws (:workspace result)]
      (workspace/cleanup-workspace ws))
    {:iteration    iteration
     :total-ms     total
     :step-ms      (double step)
     :overhead-ms  (- total (double step))
     :build-status (:build-status result)}))

(defn- run-mode
  "Run iterations for a given mode (:executor-only or :full-lifecycle)."
  [mode system pipeline job {:keys [iterations warm-up]}]
  (let [run-fn (if (= mode :executor-only)
                 #(run-single-executor-only system pipeline %)
                 #(run-single-full-lifecycle system job %))]
    ;; Warm-up (discard results)
    (log/info (str "  Warm-up (" warm-up " iterations)..."))
    (dotimes [i warm-up]
      (run-fn (inc i)))
    ;; Measured iterations
    (log/info (str "  Measuring (" iterations " iterations)..."))
    (let [results (mapv #(do
                           (when (zero? (mod (inc %) 25))
                             (log/info (str "    Progress: " (inc %) "/" iterations)))
                           (run-fn (+ warm-up (inc %))))
                        (range iterations))]
      {:summary  (stats/summarize (map :total-ms results))
       :overhead (stats/summarize (map :overhead-ms results))
       :step     (stats/summarize (map :step-ms results))
       :raw      results})))

(defn run-overhead-benchmark
  "Run the complete overhead benchmark.
   Returns {:executor-only {:summary ... :raw [...]}
            :full-lifecycle {:summary ... :raw [...]}
            :resources {:summary ...}
            :metadata {...}}"
  [config]
  (let [{:keys [iterations warm-up modes]} (:overhead config)
        ws-root (get-in config [:system :workspace-root])
        db-path (get-in config [:system :db-path])
        pipeline (create-echo-pipeline)
        monitor-state (monitor/start-monitor!
                        (get-in config [:resource-monitor :sample-interval-ms] 500))
        result (atom {})]

    ;; Executor-only mode
    (when (some #{:executor-only} modes)
      (log/info "Overhead benchmark: executor-only mode")
      (let [system (bench-system/create-lightweight-system ws-root)]
        (swap! result assoc :executor-only
               (run-mode :executor-only system pipeline nil
                         {:iterations iterations :warm-up warm-up}))))

    ;; Full-lifecycle mode
    (when (some #{:full-lifecycle} modes)
      (log/info "Overhead benchmark: full-lifecycle mode")
      (let [system (bench-system/create-full-system db-path ws-root)
            job    (bench-system/register-benchmark-job! system pipeline)]
        (try
          (swap! result assoc :full-lifecycle
                 (run-mode :full-lifecycle system pipeline job
                           {:iterations iterations :warm-up warm-up}))
          (finally
            (bench-system/cleanup! system)))))

    ;; Collect resource data
    (let [samples (monitor/stop-monitor! monitor-state)]
      (swap! result assoc :resources {:summary (monitor/resource-summary samples)
                                      :samples-count (count samples)}))

    @result))
