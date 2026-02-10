(ns chengis.db.log-search-store
  "Search across build step stdout/stderr for matching text.
   Uses LIKE-based search (database-agnostic) with Clojure-side highlighting."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Highlighting helpers
;; ---------------------------------------------------------------------------

(defn- escape-query
  "Escape special characters in a query string for safe use in SQL LIKE."
  [q]
  (-> q
      (str/replace "%" "\\%")
      (str/replace "_" "\\_")))

(defn- highlight-text
  "Wrap all case-insensitive occurrences of query in text with <mark> tags."
  [text query]
  (if (or (str/blank? text) (str/blank? query))
    text
    (let [pattern (java.util.regex.Pattern/compile
                    (java.util.regex.Pattern/quote query)
                    java.util.regex.Pattern/CASE_INSENSITIVE)]
      (str/replace text pattern (fn [match] (str "<mark>" match "</mark>"))))))

(defn- extract-matching-lines
  "Given a block of text (stdout or stderr), find lines matching query
   and return them with context lines before/after.
   Returns a seq of {:line-number N :text ... :highlighted ... :is-match bool}."
  [text query context-lines]
  (when (and (some? text) (not (str/blank? text)))
    (let [lines (str/split-lines text)
          total (count lines)
          lc-query (str/lower-case query)
          match-indices (set (keep-indexed
                              (fn [idx line]
                                (when (str/includes? (str/lower-case line) lc-query)
                                  idx))
                              lines))
          visible-indices (set (mapcat
                                (fn [idx]
                                  (range (max 0 (- idx context-lines))
                                         (min total (+ idx context-lines 1))))
                                match-indices))]
      (when (seq match-indices)
        (->> (sort visible-indices)
             (mapv (fn [idx]
                     (let [line (nth lines idx)
                           is-match (contains? match-indices idx)]
                       {:line-number (inc idx)
                        :text line
                        :highlighted (if is-match
                                       (highlight-text line query)
                                       line)
                        :is-match is-match}))))))))

(defn- process-step-result
  "Process a raw step row into a search result with matching lines."
  [row query context-lines]
  (let [stdout-matches (extract-matching-lines (:stdout row) query context-lines)
        stderr-matches (extract-matching-lines (:stderr row) query context-lines)
        all-matches (concat
                      (map #(assoc % :source "stdout") stdout-matches)
                      (map #(assoc % :source "stderr") stderr-matches))]
    (when (seq all-matches)
      {:build-id (:build-id row)
       :build-number (:build-number row)
       :job-name (:job-name row)
       :stage-name (:stage-name row)
       :step-name (:step-name row)
       :status (when (:build-status row)
                 (keyword (:build-status row)))
       :started-at (:started-at row)
       :matching-lines (vec all-matches)})))

;; ---------------------------------------------------------------------------
;; Query builders
;; ---------------------------------------------------------------------------

(defn- build-search-where
  "Build the WHERE clause for log search queries."
  [query {:keys [org-id job-name build-id status]}]
  (let [escaped (escape-query query)
        like-pattern (str "%" escaped "%")
        conditions (cond-> [[:and
                             [:= :b.org-id org-id]
                             [:or
                              [:like [:lower :bs.stdout] [:lower like-pattern]]
                              [:like [:lower :bs.stderr] [:lower like-pattern]]]]]
                     job-name (conj [:= :j.name job-name])
                     build-id (conj [:= :b.id build-id])
                     status   (conj [:= :b.status (name status)]))]
    (if (= 1 (count conditions))
      (first conditions)
      (into [:and] conditions))))

(defn- build-search-base-query
  "Build the base SELECT query for log search."
  [query opts]
  {:select [[:bs.build-id :build-id]
            [:bs.stage-name :stage-name]
            [:bs.step-name :step-name]
            [:bs.stdout :stdout]
            [:bs.stderr :stderr]
            [:bs.started-at :started-at]
            [:b.build-number :build-number]
            [:b.status :build-status]
            [:j.name :job-name]]
   :from [[:build-steps :bs]]
   :join [[:builds :b] [:= :bs.build-id :b.id]
          [:jobs :j] [:= :b.job-id :j.id]]
   :where (build-search-where query opts)
   :order-by [[:b.created-at :desc] [:bs.stage-name :asc] [:bs.step-name :asc]]})

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn search-build-logs
  "Search across build step stdout/stderr for a query string.
   Returns matches with context (surrounding lines).
   Options:
     :org-id   - required, org scoping
     :job-name - optional, filter by job
     :build-id - optional, filter by specific build
     :status   - optional, filter by build status (:success, :failure, etc)
     :limit    - max results (default 50)
     :offset   - pagination offset (default 0)
     :context-lines - lines of context around matches (default 2)"
  [ds query & {:keys [org-id job-name build-id status limit offset context-lines]
               :or {limit 50 offset 0 context-lines 2}}]
  (if (or (str/blank? query) (nil? org-id))
    []
    (let [opts {:org-id org-id :job-name job-name :build-id build-id :status status}
          base (build-search-base-query query opts)
          full (assoc base :limit limit :offset offset)
          rows (jdbc/execute! ds
                 (sql/format full)
                 {:builder-fn rs/as-unqualified-kebab-maps})]
      (->> rows
           (keep #(process-step-result % query context-lines))
           vec))))

(defn count-search-results
  "Count total matching steps for pagination."
  [ds query & {:keys [org-id job-name build-id status]}]
  (if (or (str/blank? query) (nil? org-id))
    0
    (let [opts {:org-id org-id :job-name job-name :build-id build-id :status status}
          where-clause (build-search-where query opts)
          count-query {:select [[[:count :bs.id] :cnt]]
                       :from [[:build-steps :bs]]
                       :join [[:builds :b] [:= :bs.build-id :b.id]
                              [:jobs :j] [:= :b.job-id :j.id]]
                       :where where-clause}
          result (jdbc/execute-one! ds
                   (sql/format count-query)
                   {:builder-fn rs/as-unqualified-kebab-maps})]
      (or (:cnt result) 0))))
