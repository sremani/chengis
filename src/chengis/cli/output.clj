(ns chengis.cli.output
  (:require [clojure.string :as str]))

(defn- status-indicator [status]
  (case status
    :success   "[OK]"
    :failure   "[FAIL]"
    :running   "[..]"
    :queued    "[--]"
    :pending   "[--]"
    :skipped   "[SKIP]"
    :aborted   "[ABRT]"
    (str "[" (name status) "]")))

(defn- pad-right [s n]
  (format (str "%-" n "s") (or s "")))

(defn print-header [text]
  (println)
  (println (str "=== " text " ==="))
  (println))

(defn print-table-row [& cols]
  (println (str/join "  " cols)))

(defn print-job [job]
  (print-table-row
    (pad-right (:name job) 30)
    (pad-right (:id job) 38)
    (:created-at job)))

(defn print-job-detail [job]
  (print-header (str "Job: " (:name job)))
  (println "  ID:         " (:id job))
  (println "  Created:    " (:created-at job))
  (println "  Updated:    " (:updated-at job))
  (when (:description (:pipeline job))
    (println "  Description:" (:description (:pipeline job))))
  (println)
  (println "  Stages:")
  (doseq [stage (:stages (:pipeline job))]
    (println "    -" (:stage-name stage)
             (if (:parallel? stage) "(parallel)" "(sequential)")
             (str "(" (count (:steps stage)) " steps)"))))

(defn print-build [build]
  (print-table-row
    (pad-right (status-indicator (:status build)) 8)
    (pad-right (str "#" (:build-number build)) 6)
    (pad-right (:id build) 38)
    (or (:trigger-type build) "manual")
    (or (:started-at build) "-")))

(defn print-build-detail [build stages steps]
  (print-header (str "Build #" (:build-number build)))
  (println "  ID:         " (:id build))
  (println "  Job ID:     " (:job-id build))
  (println "  Status:     " (status-indicator (:status build)) (:status build))
  (println "  Trigger:    " (or (:trigger-type build) "manual"))
  (println "  Started:    " (or (:started-at build) "-"))
  (println "  Completed:  " (or (:completed-at build) "-"))
  (println "  Workspace:  " (or (:workspace build) "-"))
  (println "  Pipeline:   " (if (= "chengisfile" (:pipeline-source build))
                               "Chengisfile" "Server"))
  (when (:git-branch build)
    (println)
    (println "  Git:")
    (println "    Branch:   " (:git-branch build))
    (println "    Commit:   " (or (:git-commit-short build) (:git-commit build)))
    (println "    Author:   " (:git-author build))
    (println "    Message:  " (:git-message build)))
  (println)
  (when (seq stages)
    (println "  Stages:")
    (doseq [stage stages]
      (println "   " (status-indicator (:status stage))
               (:stage-name stage)
               (str "(" (:started-at stage) " - " (:completed-at stage) ")"))
      ;; Show steps for this stage
      (let [stage-steps (filter #(= (:stage-name %) (:stage-name stage)) steps)]
        (doseq [step stage-steps]
          (println "      " (status-indicator (:status step))
                   (:step-name step)
                   (str "(exit: " (:exit-code step) ")")))))))

(defn print-build-log [steps]
  (doseq [step steps]
    (println (str "--- " (:stage-name step) " / " (:step-name step)
                  " [" (status-indicator (:status step)) "] ---"))
    (when (seq (:stdout step))
      (print (:stdout step)))
    (when (seq (:stderr step))
      (println "STDERR:")
      (print (:stderr step)))
    (println)))

(defn print-status [running-count queued-count total-jobs total-builds]
  (print-header "Chengis Status")
  (println "  Jobs:          " total-jobs)
  (println "  Total Builds:  " total-builds)
  (println "  Running:       " running-count)
  (println "  Queued:        " queued-count))

(defn print-error [msg]
  (println (str "Error: " msg)))

(defn print-success [msg]
  (println msg))
