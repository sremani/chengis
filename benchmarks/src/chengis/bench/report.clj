(ns chengis.bench.report
  "Results formatting and output for benchmark runs.
   Supports EDN, JSON, and human-readable text output."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]))

;; ---------------------------------------------------------------------------
;; Output writers
;; ---------------------------------------------------------------------------

(defn write-edn!
  "Write results as pretty-printed EDN."
  [results path]
  (spit path (with-out-str (pp/pprint results))))

(defn write-json!
  "Write results as JSON."
  [results path]
  (spit path (json/write-str results :key-fn #(if (keyword? %) (name %) (str %)))))

;; ---------------------------------------------------------------------------
;; Human-readable formatting
;; ---------------------------------------------------------------------------

(defn- format-summary-line
  "Format a stats summary as a single line."
  [label summary]
  (format "  %-22s median=%7.1fms  p95=%7.1fms  stddev=%6.1fms  (n=%d)"
          label
          (:median summary 0.0)
          (:p95 summary 0.0)
          (:stddev summary 0.0)
          (:n summary 0)))

(defn- format-overhead-section [overhead-data]
  (let [sb (StringBuilder.)]
    (.append sb "\n=== OVERHEAD BENCHMARK (echo hello x N) ===\n")
    (when-let [eo (:executor-only overhead-data)]
      (.append sb (str (format-summary-line "Executor-only (total):" (:summary eo)) "\n"))
      (.append sb (str (format-summary-line "  Framework overhead:" (:overhead eo)) "\n"))
      (.append sb (str (format-summary-line "  Step execution:" (:step eo)) "\n")))
    (when-let [fl (:full-lifecycle overhead-data)]
      (.append sb (str (format-summary-line "Full-lifecycle (total):" (:summary fl)) "\n"))
      (.append sb (str (format-summary-line "  Framework overhead:" (:overhead fl)) "\n"))
      (.append sb (str (format-summary-line "  Step execution:" (:step fl)) "\n")))
    (when-let [eo-median (get-in overhead-data [:executor-only :summary :median])]
      (when-let [fl-median (get-in overhead-data [:full-lifecycle :summary :median])]
        (.append sb (format "  DB overhead (median): %.1fms\n"
                            (- fl-median eo-median)))))
    (str sb)))

(defn- format-realistic-section [realistic-data]
  (let [sb (StringBuilder.)]
    (.append sb "\n=== REALISTIC BENCHMARK (clone + build + test) ===\n")
    (.append sb (str (format-summary-line "Total pipeline:" (:summary realistic-data)) "\n"))
    (.append sb (format "  Success rate: %.0f%%\n" (* 100.0 (:success-rate realistic-data 0))))
    (when-let [breakdown (:stage-breakdown realistic-data)]
      (.append sb "  Per-stage breakdown:\n")
      (doseq [[stage-name stage-stats] breakdown]
        (.append sb (str (format-summary-line (str "    " stage-name ":") stage-stats) "\n"))))
    (str sb)))

(defn- format-throughput-section [throughput-data]
  (let [sb (StringBuilder.)]
    (.append sb "\n=== THROUGHPUT BENCHMARK (concurrent builds) ===\n")
    (.append sb (format "  %-12s %12s %12s %12s\n"
                        "Concurrency" "Avg Wall(ms)" "Builds/sec" "Efficiency"))
    (.append sb (str "  " (apply str (repeat 52 "-")) "\n"))
    (let [levels (:levels throughput-data)
          base-bps (when (seq levels) (:avg-bps (first levels)))]
      (doseq [level levels]
        (let [efficiency (if (and base-bps (pos? base-bps))
                           (* 100.0 (/ (:avg-bps level)
                                       (* base-bps (:concurrency level))))
                           100.0)]
          (.append sb (format "  %-12d %12.1f %12.1f %11.0f%%\n"
                              (:concurrency level)
                              (:avg-wall-ms level)
                              (:avg-bps level)
                              efficiency)))))
    (str sb)))

(defn- format-resource-section [resources]
  (let [sb (StringBuilder.)
        summary (:summary resources)]
    (.append sb "\n=== RESOURCE USAGE ===\n")
    (when summary
      (.append sb (format "  Peak heap:       %7.1f MB\n" (:peak-heap-mb summary 0.0)))
      (.append sb (format "  Avg heap:        %7.1f MB\n" (:avg-heap-mb summary 0.0)))
      (.append sb (format "  Avg CPU load:    %7.2f\n" (:avg-cpu-load summary 0.0)))
      (.append sb (format "  GC collections:  %7d\n" (:gc-count summary 0)))
      (.append sb (format "  GC time:         %7d ms\n" (:total-gc-ms summary 0)))
      (.append sb (format "  Peak threads:    %7d\n" (:peak-threads summary 0))))
    (str sb)))

(defn print-summary
  "Print a human-readable summary to stdout."
  [results]
  (println "\n================================================================")
  (println "          CHENGIS BENCHMARK RESULTS")
  (println "================================================================")
  (when-let [meta (:metadata results)]
    (println (format "  Timestamp:  %s" (:timestamp meta "")))
    (println (format "  JVM:        %s" (:jvm-version meta "")))
    (println (format "  OS:         %s" (:os meta "")))
    (println (format "  CPU cores:  %s" (:cpu-cores meta ""))))
  (when (:overhead results)
    (print (format-overhead-section (:overhead results))))
  (when (:realistic results)
    (print (format-realistic-section (:realistic results))))
  (when (:throughput results)
    (print (format-throughput-section (:throughput results))))
  ;; Show resource summary from whichever benchmark ran last
  (let [res (or (get-in results [:throughput :resources])
                (get-in results [:realistic :resources])
                (get-in results [:overhead :resources]))]
    (when res
      (print (format-resource-section res))))
  (println "\n================================================================"))

;; ---------------------------------------------------------------------------
;; Write all formats
;; ---------------------------------------------------------------------------

(defn write-results!
  "Write all result formats to the output directory."
  [results config]
  (let [dir    (get-in config [:system :results-dir] "benchmarks/results")
        ts     (System/currentTimeMillis)
        prefix (str dir "/chengis-bench-" ts)]
    (.mkdirs (io/file dir))
    (write-edn! results (str prefix ".edn"))
    (write-json! results (str prefix ".json"))
    (spit (str prefix ".txt") (with-out-str (print-summary results)))
    (println (str "\nResults written to:"))
    (println (str "  " prefix ".edn"))
    (println (str "  " prefix ".json"))
    (println (str "  " prefix ".txt"))))
