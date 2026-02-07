(ns chengis.bench.realistic
  "Benchmark 2: Realistic Multi-Stage Pipeline.
   Measures real-world performance with checkout -> build -> test stages."
  (:require [chengis.bench.stats :as stats]
            [chengis.bench.system :as bench-system]
            [chengis.bench.resource-monitor :as monitor]
            [chengis.dsl.core :as dsl]
            [chengis.engine.executor :as executor]
            [chengis.engine.workspace :as workspace]
            [taoensso.timbre :as log]))

(defn- create-realistic-pipeline
  "Multi-stage pipeline: checkout -> build -> test.
   Uses explicit shell commands (not :source field) for Jenkins comparability."
  [repo-url branch]
  (dsl/build-pipeline 'bench-realistic {}
    [(dsl/stage "Checkout"
       (dsl/step "Clone"
         (dsl/sh (str "git clone --depth 1 --branch " branch " " repo-url " ."))))
     (dsl/stage "Build"
       (dsl/step "Compile"
         (dsl/sh "echo 'Compiling...' && ls -la")))
     (dsl/stage "Test"
       (dsl/step "Run Tests"
         (dsl/sh "echo 'Running tests...' && find . -name '*.clj' | wc -l")))]))

(defn- extract-stage-timings
  "Extract per-stage timing breakdown from a build result."
  [result]
  (mapv (fn [sr]
          {:stage    (:stage-name sr)
           :status   (:stage-status sr)
           :steps    (mapv (fn [step]
                             {:step-name   (:step-name step)
                              :duration-ms (:duration-ms step)
                              :status      (:step-status step)})
                           (:step-results sr))})
        (:stage-results result)))

(defn- run-single-realistic
  "Execute one realistic pipeline run. Returns timing breakdown."
  [system pipeline iteration]
  (let [params {:build-number iteration}
        start  (System/nanoTime)
        result (executor/run-build system pipeline params)
        end    (System/nanoTime)
        total  (/ (- end start) 1e6)]
    (when-let [ws (:workspace result)]
      (workspace/cleanup-workspace ws))
    {:iteration      iteration
     :total-ms       total
     :build-status   (:build-status result)
     :stage-timings  (extract-stage-timings result)}))

(defn run-realistic-benchmark
  "Run the realistic multi-stage benchmark.
   Returns {:summary ... :raw [...] :resources {...} :stage-breakdown {...}}"
  [config]
  (let [{:keys [iterations warm-up repo-url repo-branch]} (:realistic config)
        ws-root  (get-in config [:system :workspace-root])
        pipeline (create-realistic-pipeline repo-url (or repo-branch "master"))
        system   (bench-system/create-lightweight-system ws-root)
        monitor-state (monitor/start-monitor!
                        (get-in config [:resource-monitor :sample-interval-ms] 500))]

    ;; Warm-up
    (log/info (str "Realistic benchmark: warm-up (" warm-up " iterations)..."))
    (dotimes [i warm-up]
      (let [r (run-single-realistic system pipeline (inc i))]
        (log/info (str "  Warm-up " (inc i) ": " (:build-status r)
                       " in " (format "%.0f" (:total-ms r)) "ms"))))

    ;; Measured iterations
    (log/info (str "Realistic benchmark: measuring (" iterations " iterations)..."))
    (let [results (mapv (fn [i]
                          (let [r (run-single-realistic system pipeline
                                    (+ warm-up (inc i)))]
                            (log/info (str "  Iteration " (inc i) ": "
                                           (:build-status r)
                                           " in " (format "%.0f" (:total-ms r)) "ms"))
                            r))
                        (range iterations))
          samples (monitor/stop-monitor! monitor-state)
          ;; Compute per-stage breakdown across all successful runs
          successful   (filter #(= :success (:build-status %)) results)
          stage-names  (when (seq successful)
                         (mapv :stage (:stage-timings (first successful))))
          stage-breakdown
          (when (seq stage-names)
            (into {}
                  (map-indexed
                    (fn [idx stage-name]
                      [stage-name
                       (stats/summarize
                         (keep (fn [r]
                                 (when-let [st (get-in r [:stage-timings idx :steps 0 :duration-ms])]
                                   (double st)))
                               successful))])
                    stage-names)))]

      {:summary         (stats/summarize (map :total-ms results))
       :stage-breakdown stage-breakdown
       :success-rate    (/ (count successful) (max 1 (count results)) 1.0)
       :raw             results
       :resources       {:summary       (monitor/resource-summary samples)
                         :samples-count (count samples)}})))
