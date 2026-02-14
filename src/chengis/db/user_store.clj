(ns chengis.db.user-store
  "User CRUD operations for authentication and authorization."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [buddy.hashers :as hashers]
            [clojure.data.json :as json]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Post-mutation hook — auth cache invalidation
;; ---------------------------------------------------------------------------

;; Callback invoked after user mutations (password change, deactivation, role change).
;; Set by auth middleware to invalidate its user cache. Receives user-id.
(defonce ^:private on-user-mutated-fn (atom nil))

(defn set-on-user-mutated!
  "Register a callback to be invoked after user mutations. Called with user-id."
  [f]
  (reset! on-user-mutated-fn f))

(defn- notify-user-mutated!
  "Notify that a user was mutated (for cache invalidation)."
  [user-id]
  (when-let [f @on-user-mutated-fn]
    (try (f user-id) (catch Exception _))))

;; ---------------------------------------------------------------------------
;; Password hashing
;; ---------------------------------------------------------------------------

(defn hash-password
  "Hash a plaintext password using bcrypt."
  [password]
  (hashers/derive password {:alg :bcrypt+sha512}))

(defn check-password
  "Verify a plaintext password against a bcrypt hash."
  [password hash]
  (hashers/check password hash))

;; ---------------------------------------------------------------------------
;; User CRUD
;; ---------------------------------------------------------------------------

(defn create-user!
  "Create a new user. Returns the created user map (without password_hash)."
  [ds {:keys [username password role]}]
  (let [id (util/generate-id)
        role (or role "viewer")
        row {:id id
             :username username
             :password-hash (hash-password password)
             :role role
             :active 1}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :users
                   :values [row]}))
    {:id id :username username :role role :active true}))

(defn get-user
  "Retrieve a user by ID. Excludes password_hash for safety.
   Use get-user-with-hash for auth-internal operations that need the hash."
  [ds user-id]
  (jdbc/execute-one! ds
    (sql/format {:select [:id :username :role :active :session-version
                          :failed-attempts :locked-until :created-at :updated-at]
                 :from :users
                 :where [:= :id user-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-user-with-hash
  "Retrieve a user by ID including password_hash.
   For auth-internal use only (login, password verification).
   Callers that display user info should use get-user instead."
  [ds user-id]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :users
                 :where [:= :id user-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-user-by-username
  "Retrieve a user by username. Excludes password_hash for safety.
   Use get-user-by-username-with-hash for auth-internal operations."
  [ds username]
  (jdbc/execute-one! ds
    (sql/format {:select [:id :username :role :active :session-version
                          :failed-attempts :locked-until :created-at :updated-at]
                 :from :users
                 :where [:= :username username]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-user-by-username-with-hash
  "Retrieve a user by username including password_hash.
   For auth-internal use only (login, password verification)."
  [ds username]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :users
                 :where [:= :username username]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-users
  "List all users (without password hashes). Includes lockout fields."
  [ds]
  (jdbc/execute! ds
    (sql/format {:select [:id :username :role :active :failed-attempts :locked-until :created-at :updated-at]
                 :from :users
                 :order-by [[:created-at :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn update-user!
  "Update a user's role and/or active status.
   Bumps session_version on role or active changes to invalidate
   existing JWTs and force session re-read from DB."
  [ds user-id {:keys [role active]}]
  (let [updates (cond-> {:updated-at [:raw "CURRENT_TIMESTAMP"]}
                  role   (assoc :role role)
                  (some? active) (assoc :active (if active 1 0))
                  ;; Bump session_version on role or active changes
                  ;; to invalidate existing JWTs
                  (or role (some? active))
                  (assoc :session-version [:+ :session-version 1]))]
    (jdbc/execute-one! ds
      (sql/format {:update :users
                   :set updates
                   :where [:= :id user-id]}))
    (notify-user-mutated! user-id)))

(defn update-password!
  "Update a user's password and bump session_version to invalidate active sessions."
  [ds user-id new-password]
  (jdbc/execute-one! ds
    (sql/format {:update :users
                 :set {:password-hash (hash-password new-password)
                       :session-version [:+ :session-version 1]
                       :updated-at [:raw "CURRENT_TIMESTAMP"]}
                 :where [:= :id user-id]}))
  (notify-user-mutated! user-id))

(defn delete-user!
  "Soft-delete a user by setting active=0 and bumping session_version
   to invalidate existing JWTs."
  [ds user-id]
  (jdbc/execute-one! ds
    (sql/format {:update :users
                 :set {:active 0
                       :session-version [:+ :session-version 1]
                       :updated-at [:raw "CURRENT_TIMESTAMP"]}
                 :where [:= :id user-id]}))
  (notify-user-mutated! user-id))

(defn count-users
  "Count total users."
  [ds]
  (:count
    (jdbc/execute-one! ds
      (sql/format {:select [[[:count :*] :count]]
                   :from :users})
      {:builder-fn rs/as-unqualified-kebab-maps})))

;; ---------------------------------------------------------------------------
;; API Tokens
;; ---------------------------------------------------------------------------

(def ^:private ^:const token-prefix-length
  "Number of leading plaintext characters stored as a lookup prefix.
   12 chars from a base-36 UUID gives ~62 bits of entropy — collisions
   are astronomically unlikely, so prefix lookup returns at most 1 row."
  12)

(defn create-api-token!
  "Create an API token for a user. Returns the plaintext token (only shown once).
   The token hash is stored in the database along with a short prefix for
   O(1) index-based lookup instead of O(n) bcrypt scanning.
   Optional :scopes — a seq of scope strings like [\"build:trigger\" \"build:read\"].
   Nil scopes means full user access (backward compatible)."
  [ds {:keys [user-id name expires-at scopes]}]
  (let [id (util/generate-id)
        plaintext-token (str (util/generate-id) (util/generate-id))  ; 72-char token
        token-prefix (subs plaintext-token 0 token-prefix-length)
        token-hash (hashers/derive plaintext-token {:alg :bcrypt+sha512})
        scopes-json (when (seq scopes)
                      (json/write-str scopes))
        row (cond-> {:id id
                     :user-id user-id
                     :name name
                     :token-hash token-hash
                     :token-prefix token-prefix
                     :expires-at expires-at}
              scopes-json (assoc :scopes scopes-json))]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :api-tokens
                   :values [row]}))
    {:id id :token plaintext-token :name name :scopes scopes}))

(defn list-api-tokens
  "List API tokens for a user (without hashes). Includes scopes."
  [ds user-id]
  (jdbc/execute! ds
    (sql/format {:select [:id :name :scopes :last-used-at :expires-at :revoked-at :created-at]
                 :from :api-tokens
                 :where [:= :user-id user-id]
                 :order-by [[:created-at :desc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn- token-expired?
  "Check if an API token has expired. Nil expires-at means never expires."
  [token]
  (when-let [expires-at (:expires-at token)]
    (try
      (let [exp (Instant/parse expires-at)
            now (Instant/now)]
        (.isAfter now exp))
      (catch Exception _
        false))))

(defn find-api-token-user
  "Find the user associated with an API token.
   Uses a prefix-based index to narrow the search to at most 1-2 rows,
   then verifies with a single bcrypt check. Falls back to O(n) scan
   for legacy tokens without a prefix (created before migration 076).
   Returns the user map (with :token-scopes attached) if found, not expired, and not revoked.
   :token-scopes is nil for full-access tokens, or a set of scope strings."
  [ds plaintext-token]
  (let [prefix (when (>= (count plaintext-token) token-prefix-length)
                 (subs plaintext-token 0 token-prefix-length))
        ;; Try prefix-based lookup first (O(1) — hits the idx_api_tokens_prefix index)
        candidates (if prefix
                     (jdbc/execute! ds
                       (sql/format {:select [:id :user-id :token-hash :expires-at :revoked-at :scopes]
                                    :from :api-tokens
                                    :where [:and
                                            [:is :revoked-at nil]
                                            [:= :token-prefix prefix]]})
                       {:builder-fn rs/as-unqualified-kebab-maps})
                     [])
        ;; Fallback: if no prefix match, scan legacy tokens (those without prefix set)
        candidates (if (seq candidates)
                     candidates
                     (jdbc/execute! ds
                       (sql/format {:select [:id :user-id :token-hash :expires-at :revoked-at :scopes]
                                    :from :api-tokens
                                    :where [:and
                                            [:is :revoked-at nil]
                                            [:is :token-prefix nil]]})
                       {:builder-fn rs/as-unqualified-kebab-maps}))]
    (when-let [match (first (filter #(hashers/check plaintext-token (:token-hash %)) candidates))]
      ;; Check expiration
      (if (token-expired? match)
        (do (log/debug "API token expired:" (:id match))
            nil)
        (do
          ;; Update last-used timestamp
          (jdbc/execute-one! ds
            (sql/format {:update :api-tokens
                         :set {:last-used-at [:raw "CURRENT_TIMESTAMP"]}
                         :where [:= :id (:id match)]}))
          ;; Return the associated user with token scopes attached
          (when-let [user (get-user ds (:user-id match))]
            (let [scopes-json (:scopes match)
                  parsed-scopes (when scopes-json
                                  (try
                                    (set (json/read-str scopes-json))
                                    (catch Exception e
                                      ;; CR-07: On malformed JSON, deny all (empty set) rather than
                                      ;; nil (which means full access). Secure default.
                                      (log/error "Failed to parse scopes JSON for token" (:id match)
                                                 "— denying all scopes:" (.getMessage e))
                                      #{})))]
              (assoc user :token-scopes parsed-scopes))))))))

(defn delete-api-token!
  "Delete an API token."
  [ds token-id]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :api-tokens
                 :where [:= :id token-id]})))

(defn revoke-api-token!
  "Revoke an API token by setting revoked_at timestamp."
  [ds token-id]
  (jdbc/execute-one! ds
    (sql/format {:update :api-tokens
                 :set {:revoked-at [:raw "CURRENT_TIMESTAMP"]}
                 :where [:= :id token-id]})))

;; ---------------------------------------------------------------------------
;; Seed admin
;; ---------------------------------------------------------------------------

(defn seed-admin!
  "Create the default admin user if no users exist.
   Returns the created user or nil if users already exist."
  [ds password]
  (when (zero? (count-users ds))
    (log/info "No users found — seeding admin account")
    (create-user! ds {:username "admin"
                      :password (or password "admin")
                      :role "admin"})))
