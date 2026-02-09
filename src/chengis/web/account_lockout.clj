(ns chengis.web.account-lockout
  "Account lockout logic — locks accounts after too many failed logins.
   Config-gated: when :auth :lockout :enabled is false, all functions
   are no-ops. Uses per-user failed_attempts and locked_until columns
   on the users table."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [chengis.metrics :as metrics]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; Login attempt logging
;; ---------------------------------------------------------------------------

(defn log-login-attempt!
  "Record a login attempt (success or failure) for forensic auditing."
  [ds username ip-address success?]
  (try
    (jdbc/execute-one! ds
      (sql/format {:insert-into :login-attempts
                   :values [{:id (util/generate-id)
                             :username username
                             :ip-address ip-address
                             :success (if success? 1 0)}]}))
    (catch Exception e
      (log/debug "Failed to log login attempt:" (.getMessage e)))))

;; ---------------------------------------------------------------------------
;; Lockout state checks
;; ---------------------------------------------------------------------------

(defn- account-locked?
  "Check if the user account is currently locked.
   Returns true if locked_until is in the future."
  [user]
  (when-let [locked-until (:locked-until user)]
    (try
      (let [lock-time (Instant/parse locked-until)
            now (Instant/now)]
        (.isBefore now lock-time))
      (catch Exception _
        false))))

(defn check-lockout
  "Check if a user account is locked. Returns nil if not locked,
   or an error map {:locked true :error msg :minutes-remaining N} if locked.
   When lockout is disabled (config), always returns nil."
  [user lockout-config]
  (when (and (:enabled lockout-config)
             (account-locked? user))
    (let [locked-until (Instant/parse (:locked-until user))
          now (Instant/now)
          remaining-ms (.toMillis (Duration/between now locked-until))
          remaining-min (max 1 (int (Math/ceil (/ remaining-ms 60000.0))))]
      {:locked true
       :error (str "Account temporarily locked. Try again in " remaining-min " minute(s).")
       :minutes-remaining remaining-min})))

;; ---------------------------------------------------------------------------
;; Failure tracking
;; ---------------------------------------------------------------------------

(defn record-failed-attempt!
  "Atomically increment the failed login attempt counter for a user.
   If the count reaches max-attempts, lock the account for lockout-minutes.
   Uses SQL-level increment to avoid read-then-write race conditions.
   When lockout is disabled, this is a no-op."
  [ds user lockout-config registry]
  (when (:enabled lockout-config)
    (let [max-attempts (get lockout-config :max-attempts 5)
          lockout-minutes (get lockout-config :lockout-minutes 30)
          locked-until-str (str (.plus (Instant/now) (Duration/ofMinutes lockout-minutes)))]
      ;; Atomic increment + conditional lockout in a single statement
      (jdbc/execute-one! ds
        (sql/format {:update :users
                     :set {:failed-attempts [:+ :failed-attempts 1]
                           :locked-until [:case
                                          [:>= [:+ :failed-attempts 1] max-attempts]
                                          locked-until-str
                                          :else :locked-until]
                           :updated-at [:raw "CURRENT_TIMESTAMP"]}
                     :where [:= :id (:id user)]}))
      ;; Re-read to check if lockout was triggered
      (let [updated (jdbc/execute-one! ds
                      (sql/format {:select [:failed-attempts :locked-until]
                                   :from [:users]
                                   :where [:= :id (:id user)]})
                      {:builder-fn rs/as-unqualified-kebab-maps})]
        (when (>= (or (:failed-attempts updated) 0) max-attempts)
          (log/warn "Account locked due to" (:failed-attempts updated)
                    "failed attempts:" (:username user)
                    "locked until" (:locked-until updated))
          (try (metrics/record-account-lockout! registry) (catch Exception _)))))))

(defn reset-failed-attempts!
  "Reset the failed attempts counter after a successful login.
   Also clears any existing lockout. When lockout is disabled, this is a no-op."
  [ds user-id lockout-config]
  (when (:enabled lockout-config)
    (jdbc/execute-one! ds
      (sql/format {:update :users
                   :set {:failed-attempts 0
                         :locked-until nil
                         :updated-at [:raw "CURRENT_TIMESTAMP"]}
                   :where [:= :id user-id]}))))

;; ---------------------------------------------------------------------------
;; Admin unlock
;; ---------------------------------------------------------------------------

(defn unlock-account!
  "Admin action: unlock a user account by clearing failed_attempts and locked_until."
  [ds user-id]
  (jdbc/execute-one! ds
    (sql/format {:update :users
                 :set {:failed-attempts 0
                       :locked-until nil
                       :updated-at [:raw "CURRENT_TIMESTAMP"]}
                 :where [:= :id user-id]}))
  (log/info "Account unlocked by admin:" user-id))
