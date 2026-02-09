(ns chengis.distributed.build-queue
  "Persistent build queue for distributed dispatch.
   SQLite-backed queue with states: pending, dispatching, dispatched,
   completed, failed, dead-letter.

   Provides reliable build dispatch with retry and exponential backoff.
   All queue operations are atomic via SQLite transactions."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.data.json :as json]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- now-str [] (str (Instant/now)))

(defn- normalize-queue-item
  "Normalize a queue row: deserialize JSON fields, keywordize status."
  [row]
  (when row
    (-> row
        (update :status util/ensure-keyword)
        (update :payload #(when % (try (json/read-str % :key-fn keyword)
                                       (catch Exception _ %))))
        (update :labels #(when % (try (json/read-str % :key-fn keyword)
                                      (catch Exception _ %)))))))

;; ---------------------------------------------------------------------------
;; Queue operations
;; ---------------------------------------------------------------------------

(defn enqueue!
  "Add a build to the dispatch queue.
   Returns the queue item map with :id."
  [ds build-id job-id payload labels & [{:keys [max-retries] :or {max-retries 3}}]]
  (let [id (util/generate-id)
        now (now-str)
        row {:id id
             :build-id build-id
             :job-id job-id
             :payload (json/write-str payload)
             :labels (when (seq labels) (json/write-str (vec labels)))
             :status "pending"
             :max-retries max-retries
             :enqueued-at now}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :build-queue
                   :values [row]}))
    (log/info "Build" build-id "enqueued for dispatch (queue-id:" id ")")
    (normalize-queue-item (assoc row :retry-count 0))))

(defn dequeue-next!
  "Atomically claim the oldest pending queue item for dispatch.
   Only dequeues items whose next-retry-at is nil or in the past (backoff respected).
   Uses a status guard on the UPDATE to prevent double-claim under concurrency:
   if another processor claimed the same row first, the UPDATE matches 0 rows
   and we retry with the next candidate.
   Returns the queue item or nil if queue is empty."
  [ds]
  (jdbc/with-transaction [tx ds]
    (let [now (now-str)
          candidates (jdbc/execute! tx
                       (sql/format {:select :*
                                    :from :build-queue
                                    :where [:and
                                            [:= :status "pending"]
                                            [:or
                                             [:= :next-retry-at nil]
                                             [:<= :next-retry-at now]]]
                                    :order-by [[:enqueued-at :asc]]
                                    :limit 5})
                       {:builder-fn rs/as-unqualified-kebab-maps})]
      ;; Try each candidate until we successfully claim one
      (loop [remaining candidates]
        (when (seq remaining)
          (let [item (first remaining)
                result (jdbc/execute-one! tx
                         (sql/format {:update :build-queue
                                      :set {:status "dispatching"}
                                      :where [:and
                                              [:= :id (:id item)]
                                              [:= :status "pending"]]}))]
            (if (pos? (:next.jdbc/update-count result 0))
              ;; Successfully claimed
              (normalize-queue-item (assoc item :status "dispatching"))
              ;; Another processor got it first — try next candidate
              (recur (rest remaining)))))))))

(defn mark-dispatched!
  "Mark a queue item as successfully dispatched to an agent."
  [ds queue-id agent-id]
  (let [now (now-str)]
    (jdbc/execute-one! ds
      (sql/format {:update :build-queue
                   :set {:status "dispatched"
                         :agent-id agent-id
                         :dispatched-at now}
                   :where [:= :id queue-id]}))))

(defn mark-completed!
  "Mark a queue item as completed (build finished)."
  [ds queue-id]
  (let [now (now-str)]
    (jdbc/execute-one! ds
      (sql/format {:update :build-queue
                   :set {:status "completed"
                         :completed-at now}
                   :where [:= :id queue-id]}))))

(defn mark-completed-by-build-id!
  "Mark queue item(s) as completed by build-id (used when agent reports result)."
  [ds build-id]
  (let [now (now-str)]
    (jdbc/execute-one! ds
      (sql/format {:update :build-queue
                   :set {:status "completed"
                         :completed-at now}
                   :where [:and
                           [:= :build-id build-id]
                           [:= :status "dispatched"]]}))))

(defn mark-failed!
  "Record a dispatch failure. If retry count < max-retries, re-queue with
   exponential backoff. Otherwise, mark as dead-letter."
  [ds queue-id error & [{:keys [backoff-ms] :or {backoff-ms 1000}}]]
  (let [item (jdbc/execute-one! ds
               (sql/format {:select [:retry-count :max-retries]
                            :from :build-queue
                            :where [:= :id queue-id]})
               {:builder-fn rs/as-unqualified-kebab-maps})
        retry-count (inc (or (:retry-count item) 0))
        max-retries (or (:max-retries item) 3)]
    (if (< retry-count max-retries)
      ;; Re-queue with backoff: backoff-ms * 2^retry-count + jitter
      (let [delay-ms (* backoff-ms (Math/pow 2 retry-count))
            jitter-ms (rand-int (max 1 (int (* delay-ms 0.1))))
            next-retry (str (.plus (Instant/now)
                                   (Duration/ofMillis (long (+ delay-ms jitter-ms)))))]
        (jdbc/execute-one! ds
          (sql/format {:update :build-queue
                       :set {:status "pending"
                             :retry-count retry-count
                             :error error
                             :next-retry-at next-retry
                             :agent-id nil}
                       :where [:= :id queue-id]}))
        (log/info "Queue item" queue-id "failed (attempt" retry-count "/" max-retries
                  "), retrying after backoff:" error)
        :retrying)
      ;; Exhausted retries — dead letter
      (do
        (jdbc/execute-one! ds
          (sql/format {:update :build-queue
                       :set {:status "dead_letter"
                             :retry-count retry-count
                             :error error
                             :completed-at (now-str)}
                       :where [:= :id queue-id]}))
        (log/warn "Queue item" queue-id "moved to dead-letter after" retry-count "attempts:" error)
        :dead-letter))))

(defn requeue-for-agent!
  "Re-enqueue items that were dispatched to a specific agent.
   Used by orphan recovery when an agent goes offline.
   Returns the count of re-queued items."
  [ds agent-id]
  (let [items (jdbc/execute! ds
                (sql/format {:select [:id :retry-count :max-retries]
                             :from :build-queue
                             :where [:and
                                     [:= :agent-id agent-id]
                                     [:= :status "dispatched"]]})
                {:builder-fn rs/as-unqualified-kebab-maps})
        now (now-str)]
    (doseq [item items]
      (if (< (:retry-count item) (:max-retries item))
        (jdbc/execute-one! ds
          (sql/format {:update :build-queue
                       :set {:status "pending"
                             :agent-id nil
                             :dispatched-at nil
                             :retry-count (inc (:retry-count item))
                             :error "Agent went offline"
                             :next-retry-at now}  ;; immediate retry
                       :where [:= :id (:id item)]}))
        (jdbc/execute-one! ds
          (sql/format {:update :build-queue
                       :set {:status "dead_letter"
                             :error "Agent went offline, max retries exhausted"
                             :completed-at now}
                       :where [:= :id (:id item)]}))))
    (let [count (count items)]
      (when (pos? count)
        (log/warn "Re-queued" count "builds from offline agent" agent-id))
      count)))

;; ---------------------------------------------------------------------------
;; Query functions
;; ---------------------------------------------------------------------------

(defn get-dispatched-for-agent
  "Get all items currently dispatched to a specific agent."
  [ds agent-id]
  (mapv normalize-queue-item
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :build-queue
                   :where [:and
                           [:= :agent-id agent-id]
                           [:= :status "dispatched"]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-queue-depth
  "Count of pending items in the queue."
  [ds]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:select [[[:count :*] :cnt]]
                              :from :build-queue
                              :where [:= :status "pending"]})
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (or (:cnt result) 0)))

(defn get-oldest-pending-age-ms
  "Age in milliseconds of the oldest pending item, or 0 if queue is empty."
  [ds]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:select [[:enqueued-at :enqueued-at]]
                              :from :build-queue
                              :where [:= :status "pending"]
                              :order-by [[:enqueued-at :asc]]
                              :limit 1})
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (if-let [enqueued (:enqueued-at result)]
      (try
        (.toMillis (Duration/between (Instant/parse enqueued) (Instant/now)))
        (catch Exception _ 0))
      0)))

(defn get-queue-item-by-build-id
  "Find the queue item for a given build-id."
  [ds build-id]
  (normalize-queue-item
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :build-queue
                   :where [:= :build-id build-id]
                   :order-by [[:enqueued-at :desc]]
                   :limit 1})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-dead-letter-items
  "Get all dead-letter items (for admin inspection)."
  [ds & [{:keys [limit] :or {limit 50}}]]
  (mapv normalize-queue-item
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :build-queue
                   :where [:= :status "dead_letter"]
                   :order-by [[:completed-at :desc]]
                   :limit limit})
      {:builder-fn rs/as-unqualified-kebab-maps})))

;; ---------------------------------------------------------------------------
;; Maintenance
;; ---------------------------------------------------------------------------

(defn cleanup-completed!
  "Delete completed and dead-letter items older than retention-hours."
  [ds retention-hours]
  (let [cutoff (str (.minus (Instant/now) (Duration/ofHours retention-hours)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :build-queue
                              :where [:and
                                      [:in :status ["completed" "dead_letter"]]
                                      [:< :completed-at cutoff]]}))]
    (let [deleted (or (:next.jdbc/update-count result) 0)]
      (when (pos? deleted)
        (log/info "Cleaned up" deleted "completed/dead-letter queue items"))
      deleted)))
