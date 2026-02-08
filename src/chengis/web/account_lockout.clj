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
  "Increment the failed login attempt counter for a user.
   If the count reaches max-attempts, lock the account for lockout-minutes.
   When lockout is disabled, this is a no-op."
  [ds user lockout-config registry]
  (when (:enabled lockout-config)
    (let [max-attempts (get lockout-config :max-attempts 5)
          lockout-minutes (get lockout-config :lockout-minutes 30)
          new-count (inc (or (:failed-attempts user) 0))
          lock? (>= new-count max-attempts)
          locked-until (when lock?
                         (str (.plus (Instant/now) (Duration/ofMinutes lockout-minutes))))]
      (jdbc/execute-one! ds
        (sql/format {:update :users
                     :set (cond-> {:failed-attempts new-count
                                   :updated-at [:datetime "now"]}
                            locked-until (assoc :locked-until locked-until))
                     :where [:= :id (:id user)]}))
      (when lock?
        (log/warn "Account locked due to" new-count "failed attempts:" (:username user)
                  "locked until" locked-until)
        (try (metrics/record-account-lockout! registry) (catch Exception _))))))

(defn reset-failed-attempts!
  "Reset the failed attempts counter after a successful login.
   Also clears any existing lockout. When lockout is disabled, this is a no-op."
  [ds user-id lockout-config]
  (when (:enabled lockout-config)
    (jdbc/execute-one! ds
      (sql/format {:update :users
                   :set {:failed-attempts 0
                         :locked-until nil
                         :updated-at [:datetime "now"]}
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
                       :updated-at [:datetime "now"]}
                 :where [:= :id user-id]}))
  (log/info "Account unlocked by admin:" user-id))
