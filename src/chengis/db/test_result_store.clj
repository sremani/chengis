(ns chengis.db.test-result-store
  "Database persistence for test results and flaky test detection.
   Follows store conventions: ds as first arg, org-id scoping,
   HoneySQL for query generation."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; Test Results
;; ---------------------------------------------------------------------------

(defn save-test-results!
  "Save a batch of test results for a build."
  [ds build-id {:keys [job-id org-id stage-name step-name]} results]
  (when (seq results)
    (let [rows (mapv (fn [r]
                       {:id (util/generate-id)
                        :build-id build-id
                        :job-id job-id
                        :org-id (or org-id "default-org")
                        :stage-name stage-name
                        :step-name step-name
                        :test-name (:test-name r)
                        :test-suite (:test-suite r)
                        :status (:status r)
                        :duration-ms (:duration-ms r)
                        :error-msg (:error-msg r)})
                     results)]
      (jdbc/execute! ds
        (sql/format {:insert-into :test-results
                     :values rows})
        {:builder-fn rs/as-unqualified-kebab-maps})
      (count rows))))

(defn get-test-history
  "Get test result history for a specific test name within a job."
  [ds job-id test-name & {:keys [org-id limit] :or {limit 30}}]
  (let [conditions (cond-> [[:= :job-id job-id]
                            [:= :test-name test-name]]
                     org-id (conj [:= :org-id org-id]))
        where (into [:and] conditions)]
    (jdbc/execute! ds
      (sql/format {:select [:*]
                   :from [:test-results]
                   :where where
                   :order-by [[:created-at :desc]]
                   :limit limit})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-build-test-results
  "Get all test results for a specific build."
  [ds build-id]
  (jdbc/execute! ds
    (sql/format {:select [:*]
                 :from [:test-results]
                 :where [:= :build-id build-id]
                 :order-by [[:test-suite :asc] [:test-name :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

;; ---------------------------------------------------------------------------
;; Flaky Test Detection
;; ---------------------------------------------------------------------------

(defn compute-flaky-tests!
  "Compute and upsert flaky test records for a job.
   Analyzes test results over the last N builds (lookback-builds).
   A test is considered flaky if it has both passes and failures."
  [ds job-id {:keys [org-id lookback-builds min-runs flakiness-threshold]
              :or {lookback-builds 30 min-runs 5 flakiness-threshold 0.15}}]
  (let [;; Get recent test results for this job
        conditions (cond-> [[:= :job-id job-id]]
                     org-id (conj [:= :org-id org-id]))
        where (into [:and] conditions)
        results (jdbc/execute! ds
                  (sql/format {:select [:test-name :test-suite :status :created-at]
                               :from [:test-results]
                               :where where
                               :order-by [[:created-at :desc]]
                               :limit (* lookback-builds 200)})
                  {:builder-fn rs/as-unqualified-kebab-maps})
        ;; Group by test name and compute stats
        by-test (group-by :test-name results)
        now (str (Instant/now))
        flaky-entries
        (->> by-test
             (map (fn [[test-name runs]]
                    (let [total (count runs)
                          passes (count (filter #(= "pass" (:status %)) runs))
                          fails (count (filter #(#{"fail" "error"} (:status %)) runs))
                          rate (if (pos? total) (double (/ passes total)) 1.0)
                          score (- 1.0 (Math/abs (- (* 2.0 rate) 1.0)))
                          suite (:test-suite (first runs))
                          last-seen (:created-at (first runs))]
                      (when (and (>= total min-runs)
                                 (> score flakiness-threshold))
                        {:id (util/generate-id)
                         :org-id (or org-id "default-org")
                         :job-id job-id
                         :test-name test-name
                         :test-suite suite
                         :total-runs total
                         :pass-count passes
                         :fail-count fails
                         :flakiness-score score
                         :last-seen-at last-seen
                         :first-flaky-at now
                         :computed-at now}))))
             (filter some?))]
    ;; Delete old flaky records for this job/org and insert fresh ones
    (jdbc/execute! ds
      (sql/format {:delete-from :flaky-tests
                   :where (into [:and] conditions)}))
    (when (seq flaky-entries)
      (jdbc/execute! ds
        (sql/format {:insert-into :flaky-tests
                     :values flaky-entries})
        {:builder-fn rs/as-unqualified-kebab-maps}))
    (count flaky-entries)))

(defn list-flaky-tests
  "List flaky tests for an org, ordered by flakiness score descending."
  [ds & {:keys [org-id job-id limit] :or {limit 50}}]
  (let [conditions (cond-> []
                     org-id (conj [:= :org-id org-id])
                     job-id (conj [:= :job-id job-id]))
        query (cond-> {:select [:*]
                       :from [:flaky-tests]
                       :order-by [[:flakiness-score :desc]]
                       :limit limit}
                (seq conditions) (assoc :where (if (= 1 (count conditions))
                                                 (first conditions)
                                                 (into [:and] conditions))))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

;; ---------------------------------------------------------------------------
;; Cleanup
;; ---------------------------------------------------------------------------

(defn cleanup-old-test-results!
  "Delete test results older than retention-days. Returns count deleted."
  [ds retention-days]
  (let [cutoff (str (.minus (Instant/now) (Duration/ofDays retention-days)))
        r1 (jdbc/execute-one! ds
             (sql/format {:delete-from :test-results
                          :where [:< :created-at cutoff]}))
        r2 (jdbc/execute-one! ds
             (sql/format {:delete-from :flaky-tests
                          :where [:< :computed-at cutoff]}))]
    (+ (or (:next.jdbc/update-count r1) 0)
       (or (:next.jdbc/update-count r2) 0))))

;; ---------------------------------------------------------------------------
;; Counts
;; ---------------------------------------------------------------------------

(defn count-test-results
  "Count test result records."
  [ds & {:keys [org-id]}]
  (let [query (cond-> {:select [[[:count :*] :cnt]]
                        :from [:test-results]}
                org-id (assoc :where [:= :org-id org-id]))
        result (jdbc/execute-one! ds
                 (sql/format query)
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (or (:cnt result) 0)))

(defn count-flaky-tests
  "Count flaky test records."
  [ds & {:keys [org-id]}]
  (let [query (cond-> {:select [[[:count :*] :cnt]]
                        :from [:flaky-tests]}
                org-id (assoc :where [:= :org-id org-id]))
        result (jdbc/execute-one! ds
                 (sql/format query)
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (or (:cnt result) 0)))
