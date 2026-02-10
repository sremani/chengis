(ns chengis.db.trace-store
  "Database persistence for distributed trace spans.
   Follows store conventions: ds as first arg, org-id scoping,
   HoneySQL for query generation."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

(defn create-span!
  "Insert a new trace span record. Returns the created span map."
  [ds {:keys [trace-id span-id parent-span-id service-name operation
              kind status started-at attributes build-id org-id]}]
  (let [id (util/generate-id)
        row {:id id
             :trace-id trace-id
             :span-id span-id
             :parent-span-id parent-span-id
             :service-name (or service-name "chengis-master")
             :operation operation
             :kind (or kind "INTERNAL")
             :status (or status "OK")
             :started-at started-at
             :attributes (when attributes (pr-str attributes))
             :build-id build-id
             :org-id (or org-id "default-org")}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :trace-spans
                   :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    row))

(defn update-span!
  "Update a span with end time, duration, and status."
  [ds span-id {:keys [ended-at duration-ms status attributes]}]
  (jdbc/execute-one! ds
    (sql/format {:update :trace-spans
                 :set (cond-> {:ended-at ended-at
                               :duration-ms duration-ms}
                        status (assoc :status status)
                        attributes (assoc :attributes (pr-str attributes)))
                 :where [:= :span-id span-id]})))

(defn get-span
  "Get a single span by its span-id."
  [ds span-id]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from [:trace-spans]
                 :where [:= :span-id span-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-trace
  "Get all spans for a given trace-id, ordered by started_at."
  [ds trace-id]
  (jdbc/execute! ds
    (sql/format {:select [:*]
                 :from [:trace-spans]
                 :where [:= :trace-id trace-id]
                 :order-by [[:started-at :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-build-traces
  "Get all traces for a given build-id, ordered by creation."
  [ds build-id]
  (jdbc/execute! ds
    (sql/format {:select [:*]
                 :from [:trace-spans]
                 :where [:= :build-id build-id]
                 :order-by [[:started-at :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-traces
  "List recent traces (one root span per trace), org-scoped.
   Returns the root span (parent_span_id IS NULL) for each trace."
  [ds & {:keys [org-id limit] :or {limit 50}}]
  (let [where-clause (if org-id
                       [:and [:= :parent-span-id nil] [:= :org-id org-id]]
                       [:= :parent-span-id nil])]
    (jdbc/execute! ds
      (sql/format {:select [:*]
                   :from [:trace-spans]
                   :where where-clause
                   :order-by [[:created-at :desc]]
                   :limit limit})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn cleanup-old-traces!
  "Delete trace spans older than retention-days. Returns count deleted."
  [ds retention-days]
  (let [cutoff (str (.minus (Instant/now) (Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :trace-spans
                              :where [:< :created-at cutoff]}))]
    (or (:next.jdbc/update-count result) 0)))

(defn count-spans
  "Count total spans, optionally filtered by org-id."
  [ds & {:keys [org-id]}]
  (let [query (cond-> {:select [[[:count :*] :cnt]]
                        :from [:trace-spans]}
                org-id (assoc :where [:= :org-id org-id]))
        result (jdbc/execute-one! ds
                 (sql/format query)
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (or (:cnt result) 0)))

(defn export-trace-otlp
  "Format a trace as OTLP-compatible JSON structure for Jaeger/Tempo import.
   Returns a map matching the OTLP ResourceSpans schema."
  [ds trace-id]
  (let [spans (get-trace ds trace-id)]
    (when (seq spans)
      {:resourceSpans
       [{:resource {:attributes
                    [{:key "service.name"
                      :value {:stringValue (or (:service-name (first spans))
                                               "chengis-master")}}]}
         :scopeSpans
         [{:scope {:name "chengis" :version "1.0.0"}
           :spans (mapv (fn [span]
                          (cond-> {:traceId (:trace-id span)
                                   :spanId (:span-id span)
                                   :name (:operation span)
                                   :kind (case (:kind span)
                                           "SERVER" 2
                                           "CLIENT" 3
                                           "INTERNAL" 1
                                           1)
                                   :startTimeUnixNano (str (:started-at span))
                                   :status {:code (if (= "OK" (:status span)) 1 2)}}
                            (:parent-span-id span)
                            (assoc :parentSpanId (:parent-span-id span))
                            (:ended-at span)
                            (assoc :endTimeUnixNano (str (:ended-at span)))
                            (:attributes span)
                            (assoc :attributes
                                   (mapv (fn [[k v]]
                                           {:key (name k)
                                            :value {:stringValue (str v)}})
                                         (try (read-string (:attributes span))
                                              (catch Exception _ {}))))))
                        spans)}]}]})))
