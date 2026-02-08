(ns chengis.engine.approval
  "Approval gate engine — waits for human approval before proceeding.
   Creates approval gate records and polls for status changes.
   Integrates with the executor's stage loop."
  (:require [chengis.db.approval-store :as approval-store]
            [chengis.metrics :as metrics]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

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
   Polls the database every poll-interval-ms.
   Returns {:approved true} or {:approved false :reason \"...\"}."
  [ds gate-id {:keys [poll-interval-ms cancelled?]}]
  (let [poll-ms (or poll-interval-ms 5000)]
    (loop []
      ;; Check if build was cancelled while waiting
      (when (and cancelled? @cancelled?)
        (log/info "Approval wait cancelled for gate" gate-id)
        (approval-store/reject-gate! ds gate-id "system:cancelled"))
      (if (and cancelled? @cancelled?)
        {:approved false :reason "Build cancelled during approval wait"}
        (let [gate (approval-store/get-gate ds gate-id)]
          (case (:status gate)
            "approved"
            (do (log/info "Approval gate" gate-id "approved by" (:approved-by gate))
                {:approved true :approved-by (:approved-by gate)})

            "rejected"
            (do (log/info "Approval gate" gate-id "rejected by" (:rejected-by gate))
                {:approved false :reason (str "Rejected by " (:rejected-by gate))})

            "timed_out"
            (do (log/warn "Approval gate" gate-id "timed out")
                {:approved false :reason "Approval timed out"})

            ;; Still pending — check timeout then sleep
            (if (gate-timed-out? gate)
              (do (approval-store/timeout-gate! ds gate-id)
                  (log/warn "Approval gate" gate-id "exceeded timeout")
                  {:approved false :reason "Approval timed out"})
              (do (Thread/sleep poll-ms)
                  (recur)))))))))

;; ---------------------------------------------------------------------------
;; Stage approval check
;; ---------------------------------------------------------------------------

(defn check-stage-approval!
  "Check if a stage requires approval. If so, create a gate and wait.
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
            registry (:metrics system)
            gate-id (approval-store/create-gate! ds
                      {:build-id build-id
                       :stage-name stage-name
                       :required-role (name role)
                       :message message
                       :timeout-minutes timeout})]
        (if-not gate-id
          ;; Gate creation failed — proceed anyway (fail open, log warning)
          (do (log/warn "Could not create approval gate for" stage-name "— proceeding")
              {:proceed true})
          (do
            (log/info "Approval gate created:" gate-id "for stage" stage-name
                      "in build" build-id)
            ;; Emit SSE event so UI can show the approval request
            (when-let [event-fn (:event-fn build-ctx)]
              (event-fn {:type :approval-requested
                         :gate-id gate-id
                         :stage-name stage-name
                         :message message
                         :required-role role}))
            ;; Record metric
            (try (metrics/record-approval-requested! registry)
                 (catch Exception _))
            ;; Wait for approval
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
