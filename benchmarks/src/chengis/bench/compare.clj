(ns chengis.bench.compare
  "Comparison report generator: Chengis vs Jenkins benchmark results.
   Reads Chengis EDN and Jenkins JSON, produces side-by-side tables."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Data loading
;; ---------------------------------------------------------------------------

(defn load-chengis-results [path]
  (edn/read-string {:readers {}} (slurp path)))

(defn load-jenkins-json [path]
  (when (.exists (io/file path))
    (json/read-str (slurp path) :key-fn keyword)))

(defn- jenkins-stats
  "Calculate basic stats from a seq of Jenkins result maps."
  [results key-fn]
  (let [values (keep key-fn results)
        sorted (sort values)
        n      (count sorted)]
    (when (pos? n)
      {:n      n
       :mean   (/ (reduce + 0.0 sorted) n)
       :median (double (nth sorted (quot n 2)))
       :min    (double (first sorted))
       :max    (double (last sorted))
       :p95    (double (nth sorted (min (dec n) (int (* 0.95 n)))))})))

;; ---------------------------------------------------------------------------
;; Comparison sections
;; ---------------------------------------------------------------------------

(defn- format-row [label chengis-val jenkins-val speedup]
  (format "  %-22s %12.1f %12.1f %10.1fx"
          label
          (or chengis-val 0.0)
          (or jenkins-val 0.0)
          (or speedup 1.0)))

(defn- safe-div [a b]
  (if (and a b (pos? b)) (/ (double a) (double b)) 1.0))

(defn compare-overhead
  "Compare overhead results and print table."
  [chengis-data jenkins-data]
  (println "\n--- OVERHEAD COMPARISON (echo hello x N) ---")
  (println (format "  %-22s %12s %12s %10s" "" "Chengis(ms)" "Jenkins(ms)" "Speedup"))
  (println (str "  " (apply str (repeat 58 "-"))))

  (let [c-median (get-in chengis-data [:overhead :executor-only :summary :median])
        c-p95    (get-in chengis-data [:overhead :executor-only :summary :p95])
        c-mean   (get-in chengis-data [:overhead :executor-only :summary :mean])
        j-stats  (jenkins-stats (:results jenkins-data) :wall_ms)
        j-median (:median j-stats)
        j-p95    (:p95 j-stats)
        j-mean   (:mean j-stats)]
    (println (format-row "Median:" c-median j-median (safe-div j-median c-median)))
    (println (format-row "P95:" c-p95 j-p95 (safe-div j-p95 c-p95)))
    (println (format-row "Mean:" c-mean j-mean (safe-div j-mean c-mean)))

    ;; Also show Jenkins-reported duration (without API overhead)
    (when-let [j-internal (jenkins-stats (:results jenkins-data) :jenkins_ms)]
      (println "")
      (println "  Jenkins internal duration (excludes REST API polling):")
      (println (format "    Median: %.1fms  Mean: %.1fms" (:median j-internal) (:mean j-internal))))))

(defn compare-realistic
  "Compare realistic benchmark results."
  [chengis-data jenkins-data]
  (println "\n--- REALISTIC COMPARISON (clone + build + test) ---")
  (println (format "  %-22s %12s %12s %10s" "" "Chengis(ms)" "Jenkins(ms)" "Speedup"))
  (println (str "  " (apply str (repeat 58 "-"))))

  (let [c-summary (get-in chengis-data [:realistic :summary])
        j-stats   (jenkins-stats (:results jenkins-data) :wall_ms)]
    (when (and c-summary j-stats)
      (println (format-row "Median:" (:median c-summary) (:median j-stats)
                           (safe-div (:median j-stats) (:median c-summary))))
      (println (format-row "Mean:" (:mean c-summary) (:mean j-stats)
                           (safe-div (:mean j-stats) (:mean c-summary)))))))

(defn compare-throughput
  "Compare throughput benchmark results."
  [chengis-data jenkins-data]
  (println "\n--- THROUGHPUT COMPARISON (builds/sec) ---")
  (println (format "  %-12s %12s %12s %10s" "Concurrency" "Chengis" "Jenkins" "Ratio"))
  (println (str "  " (apply str (repeat 48 "-"))))

  (let [c-levels (get-in chengis-data [:throughput :levels])
        j-levels (:levels jenkins-data)]
    (doseq [c-level c-levels]
      (let [c (:concurrency c-level)
            j-level (first (filter #(= c (:concurrency %)) j-levels))
            c-bps (:avg-bps c-level)
            j-bps (when j-level
                    (let [batches (:batches j-level)]
                      (when (seq batches)
                        (/ (reduce + 0.0 (map :builds_per_sec batches))
                           (count batches)))))]
        (println (format "  %-12d %12.1f %12.1f %9.1fx"
                         c
                         (or c-bps 0.0)
                         (or j-bps 0.0)
                         (safe-div c-bps j-bps)))))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn generate-report
  "Generate a full comparison report."
  [chengis-path jenkins-dir]
  (let [chengis      (load-chengis-results chengis-path)
        j-overhead   (load-jenkins-json (str jenkins-dir "/jenkins-overhead.json"))
        j-realistic  (load-jenkins-json (str jenkins-dir "/jenkins-realistic.json"))
        j-throughput (load-jenkins-json (str jenkins-dir "/jenkins-throughput.json"))]

    (println "\n================================================================")
    (println "       CHENGIS vs JENKINS — PERFORMANCE COMPARISON")
    (println "================================================================")

    (when-let [meta (:metadata chengis)]
      (println (format "  Timestamp:  %s" (:timestamp meta)))
      (println (format "  System:     %s, %d cores" (:os meta) (:cpu-cores meta)))
      (println (format "  JVM:        %s" (:jvm-version meta))))

    (when (and (:overhead chengis) j-overhead)
      (compare-overhead chengis j-overhead))

    (when (and (:realistic chengis) j-realistic)
      (compare-realistic chengis j-realistic))

    (when (and (:throughput chengis) j-throughput)
      (compare-throughput chengis j-throughput))

    (println "\n================================================================")
    (println "  Note: Jenkins times include REST API polling overhead (~200ms).")
    (println "  Check Jenkins internal duration for fairer comparison.")
    (println "================================================================")))

(defn -main [& args]
  (let [chengis-path (or (first args) "benchmarks/results/chengis-latest.edn")
        jenkins-dir  (or (second args) "benchmarks/results")]
    (if (.exists (io/file chengis-path))
      (generate-report chengis-path jenkins-dir)
      (do
        (println "Usage: generate-report.clj <chengis-results.edn> [jenkins-results-dir]")
        (println "  Chengis results file not found:" chengis-path)))))
