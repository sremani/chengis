(ns chengis.db.audit-export
  "Export audit logs as CSV or JSON for compliance and SIEM integration."
  (:require [chengis.db.audit-store :as audit-store]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.io Writer]))

(def ^:private csv-columns
  "Column ordering for CSV export."
  [:timestamp :username :action :resource-type :resource-id :ip-address :detail])

(def ^:private csv-header
  "timestamp,username,action,resource_type,resource_id,ip_address,detail")

(defn- escape-csv-field
  "Escape a field for CSV: wrap in quotes if it contains comma, quote, or newline.
   Also prevents CSV formula injection by prefixing dangerous characters (=, +, -, @)
   with a single-quote, so spreadsheet apps won't execute them as formulas."
  [v]
  (let [s (str (if (nil? v) "" v))
        ;; Prevent CSV formula injection (=, +, -, @)
        safe (if (and (seq s) (#{\= \+ \- \@} (first s)))
               (str "'" s)
               s)]
    (if (re-find #"[,\"\n\r]" safe)
      (str "\"" (str/replace safe "\"" "\"\"") "\"")
      safe)))

(defn- audit->csv-row
  "Convert an audit record to a CSV row string."
  [audit]
  (str/join ","
    (map (fn [col]
           (let [v (get audit col)]
             (escape-csv-field
               (if (= col :detail)
                 (when v (pr-str v))
                 v))))
         csv-columns)))

(defn- audit->json-record
  "Convert an audit record to a clean map for JSON output."
  [audit]
  {:timestamp     (:timestamp audit)
   :username      (:username audit)
   :action        (:action audit)
   :resource_type (:resource-type audit)
   :resource_id   (:resource-id audit)
   :ip_address    (:ip-address audit)
   :detail        (:detail audit)})

(def ^:private batch-size
  "Number of records per batch for streaming export."
  500)

(defn export-csv
  "Stream audit logs as CSV to a java.io.Writer.
   Writes header row followed by data rows in batches.
   Filters are passed through to audit-store/query-audits.
   Supports :org-id in filters for org-scoped exports."
  [ds filters ^Writer writer]
  (.write writer csv-header)
  (.write writer "\n")
  (loop [offset 0]
    (let [batch (audit-store/query-audits ds
                  (merge filters {:limit batch-size :offset offset}))]
      (doseq [audit batch]
        (.write writer (audit->csv-row audit))
        (.write writer "\n"))
      (when (= (count batch) batch-size)
        (recur (+ offset batch-size)))))
  (.flush writer))

(defn export-json
  "Stream audit logs as a JSON array to a java.io.Writer.
   Reads in batches for memory efficiency.
   Filters are passed through to audit-store/query-audits.
   Supports :org-id in filters for org-scoped exports."
  [ds filters ^Writer writer]
  (.write writer "[")
  (loop [offset 0
         total-written 0]
    (let [batch (audit-store/query-audits ds
                  (merge filters {:limit batch-size :offset offset}))
          written (reduce (fn [n audit]
                            (when (pos? (+ total-written n))
                              (.write writer ","))
                            (.write writer (json/write-str (audit->json-record audit)))
                            (inc n))
                          0 batch)]
      (when (= (count batch) batch-size)
        (recur (+ offset batch-size) (+ total-written written)))))
  (.write writer "]")
  (.flush writer))

(defn export-count
  "Count the number of audit events matching the given filters.
   Supports :org-id in filters for org-scoped counts."
  [ds filters]
  (audit-store/count-audits ds filters))
