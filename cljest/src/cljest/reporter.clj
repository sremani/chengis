(ns cljest.reporter
  "Report generation for mutation testing results.
   Produces text reports (stdout) and standalone HTML reports."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Scoring
;; ---------------------------------------------------------------------------

(defn mutation-score
  "Calculate the mutation score (percentage of killed mutations).
   Returns a double 0.0-100.0."
  [results]
  (if (empty? results)
    100.0
    (let [total (count results)
          killed (count (filter #(= :killed (:status %)) results))
          timed-out (count (filter #(= :timed-out (:status %)) results))
          ;; Timed-out mutations count as killed (the mutation broke something)
          effective-killed (+ killed timed-out)]
      (* 100.0 (/ (double effective-killed) total)))))

(defn- format-duration
  "Format nanoseconds as human-readable duration."
  [duration-ns]
  (let [ms (/ duration-ns 1e6)
        secs (/ ms 1000.0)]
    (cond
      (< secs 60) (format "%.1fs" secs)
      (< secs 3600) (format "%dm %ds" (int (/ secs 60)) (int (mod secs 60)))
      :else (format "%dh %dm" (int (/ secs 3600)) (int (mod (/ secs 60) 60))))))

;; ---------------------------------------------------------------------------
;; Text report
;; ---------------------------------------------------------------------------

(defn text-report
  "Generate and print a text report to stdout.
   Arguments:
     report-data — {:results [...] :duration-ns N :source-ns-count N :test-ns-count N}"
  [{:keys [results duration-ns source-ns-count test-ns-count config]}]
  (let [total (count results)
        killed (count (filter #(= :killed (:status %)) results))
        survived (count (filter #(= :survived (:status %)) results))
        timed-out (count (filter #(= :timed-out (:status %)) results))
        errors (count (filter #(= :error (:status %)) results))
        score (mutation-score results)
        by-ns (group-by :source-ns results)]
    (println)
    (println "================================================================")
    (println "              CLJEST MUTATION TESTING REPORT")
    (println "================================================================")
    (println (format "  Source namespaces: %-8d Mutations generated: %d"
                     source-ns-count total))
    (println (format "  Test namespaces:  %-8d Mutations killed:   %d (%.1f%%)"
                     test-ns-count killed (if (pos? total)
                                            (* 100.0 (/ (double killed) total))
                                            0.0)))
    (println (format "  Duration:         %-8s Mutations survived: %d (%.1f%%)"
                     (format-duration (or duration-ns 0))
                     survived
                     (if (pos? total) (* 100.0 (/ (double survived) total)) 0.0)))
    (when (pos? timed-out)
      (println (format "                             Timed out:          %d" timed-out)))
    (when (pos? errors)
      (println (format "                             Errors:             %d" errors)))
    (println (format "  Mutation score:   %.1f%%" score))
    (println "================================================================")

    ;; Per-namespace breakdown
    (when (seq by-ns)
      (println)
      (println "  Namespace                                Score    Killed/Total")
      (println "  ─────────────────────────────────────────────────────────────")
      (doseq [[ns-sym ns-results] (sort-by first by-ns)]
        (let [ns-total (count ns-results)
              ns-killed (count (filter #(#{:killed :timed-out} (:status %)) ns-results))
              ns-score (if (pos? ns-total)
                         (* 100.0 (/ (double ns-killed) ns-total))
                         0.0)]
          (println (format "  %-40s %5.1f%%   %d/%d"
                           (str ns-sym) ns-score ns-killed ns-total)))))

    ;; Survivors
    (let [survivors (filter #(= :survived (:status %)) results)]
      (when (seq survivors)
        (println)
        (println (format "  SURVIVORS (%d total, showing up to 20):" (count survivors)))
        (println)
        (doseq [s (take 20 survivors)]
          (println "  ┌──────────────────────────────────────────────────────────┐")
          (println (format "  │ %s:%d"
                           (or (:source-file s) (str (:source-ns s)))
                           (first (:position s))))
          (println (format "  │ Operator: %s" (name (:operator-id s))))
          (when (:original-form s)
            (println (format "  │ Original: %s" (pr-str (:original-form s)))))
          (println "  └──────────────────────────────────────────────────────────┘")
          (println))))

    ;; Threshold check
    (let [threshold (get config :threshold 80)]
      (if (>= score threshold)
        (println (format "  ✓ Score %.1f%% meets threshold %d%%" score threshold))
        (println (format "  ✗ Score %.1f%% is BELOW threshold %d%%" score threshold))))
    (println)))

;; ---------------------------------------------------------------------------
;; HTML report
;; ---------------------------------------------------------------------------

(defn- html-score-color
  "Return a CSS color for a mutation score."
  [score]
  (cond
    (>= score 90) "#22c55e"  ; green
    (>= score 75) "#eab308"  ; yellow
    (>= score 50) "#f97316"  ; orange
    :else "#ef4444"))         ; red

(defn- escape-html
  "Escape HTML special characters."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn html-report
  "Generate a standalone HTML report file.
   Arguments:
     report-data — {:results [...] :duration-ns N :source-ns-count N :test-ns-count N}
     output-dir  — directory to write the HTML file"
  [{:keys [results duration-ns source-ns-count test-ns-count] :as report-data}
   output-dir]
  (let [total (count results)
        killed (count (filter #(= :killed (:status %)) results))
        survived (count (filter #(= :survived (:status %)) results))
        timed-out (count (filter #(= :timed-out (:status %)) results))
        score (mutation-score results)
        by-ns (group-by :source-ns results)
        survivors (filter #(= :survived (:status %)) results)
        timestamp (str (java.time.Instant/now))

        html (str
               "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n"
               "<meta charset=\"UTF-8\">\n"
               "<title>cljest Mutation Testing Report</title>\n"
               "<style>\n"
               "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; "
               "max-width: 1000px; margin: 0 auto; padding: 20px; background: #f8fafc; color: #1e293b; }\n"
               "h1 { color: #0f172a; border-bottom: 3px solid #3b82f6; padding-bottom: 10px; }\n"
               ".summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin: 20px 0; }\n"
               ".card { background: white; border-radius: 8px; padding: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); text-align: center; }\n"
               ".card .value { font-size: 2em; font-weight: bold; }\n"
               ".card .label { color: #64748b; font-size: 0.9em; }\n"
               "table { width: 100%; border-collapse: collapse; background: white; border-radius: 8px; overflow: hidden; "
               "box-shadow: 0 1px 3px rgba(0,0,0,0.1); margin: 20px 0; }\n"
               "th { background: #f1f5f9; padding: 12px; text-align: left; font-weight: 600; }\n"
               "td { padding: 12px; border-top: 1px solid #e2e8f0; }\n"
               ".survivor { background: white; border-left: 4px solid #ef4444; padding: 12px 16px; margin: 8px 0; "
               "border-radius: 0 4px 4px 0; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }\n"
               ".survivor .location { font-weight: 600; color: #1e293b; }\n"
               ".survivor .operator { color: #64748b; }\n"
               ".survivor code { background: #f1f5f9; padding: 2px 6px; border-radius: 3px; font-size: 0.9em; }\n"
               ".badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 0.8em; font-weight: 600; color: white; }\n"
               "footer { color: #94a3b8; font-size: 0.85em; margin-top: 40px; padding-top: 20px; border-top: 1px solid #e2e8f0; }\n"
               "</style>\n</head>\n<body>\n"

               "<h1>cljest Mutation Testing Report</h1>\n"

               ;; Summary cards
               "<div class=\"summary\">\n"
               "<div class=\"card\"><div class=\"value\" style=\"color: "
               (html-score-color score) "\">"
               (format "%.1f%%" score) "</div><div class=\"label\">Mutation Score</div></div>\n"
               "<div class=\"card\"><div class=\"value\">" (str total)
               "</div><div class=\"label\">Total Mutations</div></div>\n"
               "<div class=\"card\"><div class=\"value\" style=\"color: #22c55e\">" (str killed)
               "</div><div class=\"label\">Killed</div></div>\n"
               "<div class=\"card\"><div class=\"value\" style=\"color: #ef4444\">" (str survived)
               "</div><div class=\"label\">Survived</div></div>\n"
               "</div>\n"

               ;; Duration and metadata
               "<p>Duration: " (format-duration (or duration-ns 0))
               " | Namespaces: " (str source-ns-count)
               " source, " (str test-ns-count) " test"
               (when (pos? timed-out) (str " | Timed out: " timed-out))
               "</p>\n"

               ;; Namespace table
               "<h2>Per-Namespace Results</h2>\n"
               "<table>\n<tr><th>Namespace</th><th>Score</th><th>Killed</th>"
               "<th>Survived</th><th>Total</th></tr>\n"
               (str/join
                 (for [[ns-sym ns-results] (sort-by first by-ns)]
                   (let [ns-total (count ns-results)
                         ns-killed (count (filter #(#{:killed :timed-out} (:status %)) ns-results))
                         ns-survived (count (filter #(= :survived (:status %)) ns-results))
                         ns-score (if (pos? ns-total)
                                    (* 100.0 (/ (double ns-killed) ns-total))
                                    0.0)]
                     (str "<tr><td>" (escape-html (str ns-sym)) "</td>"
                          "<td><span class=\"badge\" style=\"background: "
                          (html-score-color ns-score) "\">"
                          (format "%.1f%%" ns-score) "</span></td>"
                          "<td>" ns-killed "</td>"
                          "<td>" ns-survived "</td>"
                          "<td>" ns-total "</td></tr>\n"))))
               "</table>\n"

               ;; Survivors
               (when (seq survivors)
                 (str "<h2>Surviving Mutations (" (count survivors) ")</h2>\n"
                      (str/join
                        (for [s survivors]
                          (str "<div class=\"survivor\">"
                               "<div class=\"location\">"
                               (escape-html (or (:source-file s) (str (:source-ns s))))
                               ":" (first (:position s)) "</div>"
                               "<div class=\"operator\">Operator: <code>"
                               (escape-html (name (:operator-id s))) "</code></div>"
                               (when (:original-form s)
                                 (str "<div>Original: <code>"
                                      (escape-html (pr-str (:original-form s)))
                                      "</code></div>"))
                               "</div>\n")))))

               "<footer>Generated by cljest v0.1.0 at " timestamp "</footer>\n"
               "</body>\n</html>\n")]
    ;; Ensure output directory exists
    (.mkdirs (io/file output-dir))
    (let [output-path (str output-dir "/mutation-report.html")]
      (spit output-path html)
      output-path)))
