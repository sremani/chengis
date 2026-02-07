(ns chengis.db.user-store
  "User CRUD operations for authentication and authorization."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [buddy.hashers :as hashers]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

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
  "Retrieve a user by ID."
  [ds user-id]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :users
                 :where [:= :id user-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-user-by-username
  "Retrieve a user by username."
  [ds username]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :users
                 :where [:= :username username]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-users
  "List all users (without password hashes)."
  [ds]
  (jdbc/execute! ds
    (sql/format {:select [:id :username :role :active :created-at :updated-at]
                 :from :users
                 :order-by [[:created-at :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn update-user!
  "Update a user's role and/or active status."
  [ds user-id {:keys [role active]}]
  (let [updates (cond-> {:updated-at [:datetime "now"]}
                  role   (assoc :role role)
                  (some? active) (assoc :active (if active 1 0)))]
    (jdbc/execute-one! ds
      (sql/format {:update :users
                   :set updates
                   :where [:= :id user-id]}))))

(defn update-password!
  "Update a user's password."
  [ds user-id new-password]
  (jdbc/execute-one! ds
    (sql/format {:update :users
                 :set {:password-hash (hash-password new-password)
                       :updated-at [:datetime "now"]}
                 :where [:= :id user-id]})))

(defn delete-user!
  "Soft-delete a user by setting active=0."
  [ds user-id]
  (jdbc/execute-one! ds
    (sql/format {:update :users
                 :set {:active 0
                       :updated-at [:datetime "now"]}
                 :where [:= :id user-id]})))

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

(defn create-api-token!
  "Create an API token for a user. Returns the plaintext token (only shown once).
   The token hash is stored in the database."
  [ds {:keys [user-id name expires-at]}]
  (let [id (util/generate-id)
        plaintext-token (str (util/generate-id) (util/generate-id))  ; 72-char token
        token-hash (hashers/derive plaintext-token {:alg :bcrypt+sha512})
        row {:id id
             :user-id user-id
             :name name
             :token-hash token-hash
             :expires-at expires-at}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :api-tokens
                   :values [row]}))
    {:id id :token plaintext-token :name name}))

(defn list-api-tokens
  "List API tokens for a user (without hashes)."
  [ds user-id]
  (jdbc/execute! ds
    (sql/format {:select [:id :name :last-used-at :expires-at :created-at]
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
  "Find the user associated with an API token by checking the token against stored hashes.
   Returns the user map if found and token is not expired, nil otherwise.
   Note: This is O(n) over tokens — acceptable for typical token counts."
  [ds plaintext-token]
  (let [tokens (jdbc/execute! ds
                 (sql/format {:select [:api-tokens/id :api-tokens/user-id :api-tokens/token-hash
                                       :api-tokens/expires-at]
                              :from :api-tokens})
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (when-let [match (first (filter #(hashers/check plaintext-token (:token-hash %)) tokens))]
      ;; Check expiration
      (if (token-expired? match)
        (do (log/debug "API token expired:" (:id match))
            nil)
        (do
          ;; Update last-used timestamp
          (jdbc/execute-one! ds
            (sql/format {:update :api-tokens
                         :set {:last-used-at [:datetime "now"]}
                         :where [:= :id (:id match)]}))
          ;; Return the associated user
          (get-user ds (:user-id match)))))))

(defn delete-api-token!
  "Delete an API token."
  [ds token-id]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :api-tokens
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
