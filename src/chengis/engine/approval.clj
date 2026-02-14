(ns chengis.engine.approval
  "Approval gate engine — waits for human approval before proceeding.
   Uses core.async channels with a polling fallback so the build thread is
   *parked* (not blocked) while waiting. This prevents approval gates from
   exhausting the core.async thread pool.
   Integrates with the executor's stage loop."
  (:require [chengis.db.approval-store :as approval-store]
            [chengis.metrics :as metrics]
            [clojure.core.async :as async :refer [go-loop <! >! chan close! timeout alts!]]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; Notification channels — allows instant wake-up when a gate is resolved
;; ---------------------------------------------------------------------------

;; gate-id -> set of channels waiting for notification
(defonce ^:private gate-waiters (atom {}))

(defn notify-gate-resolved!
  "Called by the web handler (approve/reject) to wake up any waiting build
   thread immediately instead of waiting for the next poll cycle.
   This is a best-effort notification — the poll loop is the safety net."
  [gate-id]
  (when-let [chs (get @gate-waiters gate-id)]
    (doseq [ch chs]
      (async/put! ch :resolved))
    (log/debug "Notified" (count chs) "waiters for gate" gate-id)))

(defn- register-waiter! [gate-id ch]
  (swap! gate-waiters update gate-id (fnil conj #{}) ch))

(defn- unregister-waiter! [gate-id ch]
  (swap! gate-waiters update gate-id disj ch)
  ;; Clean up empty sets
  (swap! gate-waiters (fn [m]
                        (if (empty? (get m gate-id))
                          (dissoc m gate-id)
                          m))))

;; ---------------------------------------------------------------------------
;; Gate waiting logic
;; ---------------------------------------------------------------------------

(defn- parse-datetime
  "Parse a datetime string from SQLite (YYYY-MM-DD HH:MM:SS) or ISO-8601."
  [s]
  (when s
    (let [normalized (if (and (> (count s) 10) (= \space (.charAt s 10)))
                       (str (subs s 0 10) "T" (subs s 11) "Z")
                       (if (.endsWith s "Z") s (str s "Z")))]
      (Instant/parse normalized))))

(defn- gate-timed-out?
  "Check if an approval gate has exceeded its timeout."
  [gate]
  (when-let [timeout-minutes (:timeout-minutes gate)]
    (let [created-at (parse-datetime (:created-at gate))
          deadline (.plus created-at (Duration/ofMinutes timeout-minutes))
          now (Instant/now)]
      (.isAfter now deadline))))

(defn wait-for-approval!
  "Wait for an approval gate to be approved/rejected/timed-out.
   Uses a core.async go-loop that parks (not blocks) the thread between
   poll cycles. A notification channel provides instant wake-up when the
   gate is resolved via the web UI, so in practice the thread is released
   almost immediately after approval.
   Returns a promise (deliver) with {:approved true} or {:approved false :reason ...}."
  [ds gate-id {:keys [poll-interval-ms cancelled?]}]
  (let [poll-ms (or poll-interval-ms 5000)
        result-promise (promise)
        notify-ch (chan 1)]
    ;; Register for instant notifications
    (register-waiter! gate-id notify-ch)
    ;; Async poll loop — parks the thread instead of blocking it
    (go-loop []
      ;; Wait for either notification or poll timeout
      (let [[_ port] (alts! [notify-ch (timeout poll-ms)])]
        ;; Check cancellation
        (if (and cancelled? @cancelled?)
          (do
            (log/info "Approval wait cancelled for gate" gate-id)
            (try (approval-store/reject-gate! ds gate-id "system:cancelled")
                 (catch Exception _))
            (deliver result-promise
                     {:approved false :reason "Build cancelled during approval wait"}))
          ;; Check gate status
          (let [gate (approval-store/get-gate ds gate-id)]
            (case (:status gate)
              "approved"
              (do (log/info "Approval gate" gate-id "approved by" (:approved-by gate))
                  (deliver result-promise
                           {:approved true :approved-by (:approved-by gate)}))

              "rejected"
              (do (log/info "Approval gate" gate-id "rejected by" (:rejected-by gate))
                  (deliver result-promise
                           {:approved false :reason (str "Rejected by " (:rejected-by gate))}))

              "timed_out"
              (do (log/warn "Approval gate" gate-id "timed out")
                  (deliver result-promise
                           {:approved false :reason "Approval timed out"}))

              ;; Still pending — check timeout
              (if (gate-timed-out? gate)
                (do (approval-store/timeout-gate! ds gate-id)
                    (log/warn "Approval gate" gate-id "exceeded timeout")
                    (deliver result-promise
                             {:approved false :reason "Approval timed out"}))
                ;; Continue waiting (loop parks, doesn't block)
                (recur)))))))
    ;; Block the calling thread only via deref on the promise
    ;; The key difference: the go-loop above runs on the core.async
    ;; dispatch thread pool (which has many more threads) and parks between
    ;; polls. The build thread blocks on deref but can be interrupted.
    (let [result (deref result-promise)]
      (unregister-waiter! gate-id notify-ch)
      (close! notify-ch)
      result)))

;; ---------------------------------------------------------------------------
;; Stage approval check
;; ---------------------------------------------------------------------------

(defn check-stage-approval!
  "Check if a stage requires approval. If so, create a gate and wait.
   Supports multi-approver via :approver-group and :min-approvals in the
   stage's :approval map.
   Returns {:proceed true} or {:proceed false :reason \"...\"}."
  [system build-ctx stage-def]
  (let [approval (:approval stage-def)]
    (if-not approval
      ;; No approval needed — proceed immediately
      {:proceed true}
      (let [ds (:db system)
            build-id (:build-id build-ctx)
            stage-name (:stage-name stage-def)
            message (or (:message approval) (str "Approve stage: " stage-name))
            role (or (:role approval) "developer")
            timeout (or (:timeout-minutes approval) 1440)
            approver-group (:approver-group approval)
            min-approvals (or (:min-approvals approval) 1)
            registry (:metrics system)
            gate-id (approval-store/create-gate! ds
                      {:build-id build-id
                       :stage-name stage-name
                       :required-role (name role)
                       :message message
                       :timeout-minutes timeout
                       :approver-group approver-group
                       :min-approvals min-approvals})]
        (if-not gate-id
          ;; Gate creation failed — fail closed (abort stage for safety)
          (do (log/warn "Could not create approval gate for" stage-name
                        "— aborting stage (fail-closed)")
              (try (metrics/record-approval-resolved! registry "error")
                   (catch Exception _))
              {:proceed false :reason "Approval gate creation failed — stage aborted for safety"})
          (do
            (log/info "Approval gate created:" gate-id "for stage" stage-name
                      "in build" build-id)
            ;; Emit SSE event so UI can show the approval request
            (when-let [event-fn (:event-fn build-ctx)]
              (event-fn {:event-type :approval-requested
                         :build-id (:build-id build-ctx)
                         :gate-id gate-id
                         :stage-name stage-name
                         :message message
                         :required-role role}))
            ;; Record metric
            (try (metrics/record-approval-requested! registry)
                 (catch Exception _))
            ;; Wait for approval (async — thread parks between polls)
            (let [poll-ms (get-in system [:config :approvals :poll-interval-ms] 5000)
                  result (wait-for-approval! ds gate-id
                           {:poll-interval-ms poll-ms
                            :cancelled? (:cancelled? build-ctx)})]
              (if (:approved result)
                (do (try (metrics/record-approval-resolved! registry "approved")
                         (catch Exception _))
                    {:proceed true})
                (do (try (metrics/record-approval-resolved! registry "rejected")
                         (catch Exception _))
                    {:proceed false :reason (:reason result)})))))))))
