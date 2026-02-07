(ns chengis.db.secret-store
  "Encrypted secrets storage for CI/CD pipelines.
   Secrets are encrypted at rest using AES-256-GCM. The master key comes from
   config (:secrets :master-key) or the CHENGIS_SECRET_KEY environment variable."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util])
  (:import [javax.crypto Cipher KeyGenerator SecretKey]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]
           [java.security SecureRandom]
           [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Encryption helpers (AES-256-GCM)
;; ---------------------------------------------------------------------------

(def ^:private gcm-tag-bits 128)
(def ^:private iv-bytes 12)

(defn- derive-key
  "Derive a 256-bit AES key from a master key string.
   Uses SHA-256 hash of the key string to produce exactly 32 bytes."
  [master-key-str]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        hash (.digest digest (.getBytes ^String master-key-str "UTF-8"))]
    (SecretKeySpec. hash "AES")))

(defn- get-master-key
  "Get the AES master key from config or environment."
  [config]
  (let [key-str (or (get-in config [:secrets :master-key])
                     (System/getenv "CHENGIS_SECRET_KEY")
                     ;; Default key for development (NOT for production!)
                     "chengis-dev-secret-key-change-me")]
    (derive-key key-str)))

(defn- encrypt
  "Encrypt plaintext using AES-256-GCM. Returns Base64-encoded string (IV + ciphertext)."
  [^SecretKey key ^String plaintext]
  (let [iv (byte-array iv-bytes)
        _ (.nextBytes (SecureRandom.) iv)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")
        spec (GCMParameterSpec. gcm-tag-bits iv)]
    (.init cipher Cipher/ENCRYPT_MODE key spec)
    (let [ciphertext (.doFinal cipher (.getBytes plaintext "UTF-8"))
          combined (byte-array (+ iv-bytes (alength ciphertext)))]
      (System/arraycopy iv 0 combined 0 iv-bytes)
      (System/arraycopy ciphertext 0 combined iv-bytes (alength ciphertext))
      (.encodeToString (Base64/getEncoder) combined))))

(defn- decrypt
  "Decrypt a Base64-encoded AES-256-GCM string. Returns plaintext."
  [^SecretKey key ^String encoded]
  (let [combined (.decode (Base64/getDecoder) encoded)
        iv (byte-array iv-bytes)
        ciphertext (byte-array (- (alength combined) iv-bytes))]
    (System/arraycopy combined 0 iv 0 iv-bytes)
    (System/arraycopy combined iv-bytes ciphertext 0 (alength ciphertext))
    (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
          spec (GCMParameterSpec. gcm-tag-bits iv)]
      (.init cipher Cipher/DECRYPT_MODE key spec)
      (String. (.doFinal cipher ciphertext) "UTF-8"))))

;; ---------------------------------------------------------------------------
;; CRUD operations
;; ---------------------------------------------------------------------------

(defn set-secret!
  "Create or update a secret. Scope is 'global' or a job-id."
  [ds config secret-name value & {:keys [scope] :or {scope "global"}}]
  (let [key (get-master-key config)
        encrypted (encrypt key value)
        existing (jdbc/execute-one! ds
                   (sql/format {:select [:id]
                                :from :secrets
                                :where [:and [:= :scope scope] [:= :name secret-name]]})
                   {:builder-fn rs/as-unqualified-kebab-maps})]
    (if existing
      ;; Update
      (jdbc/execute-one! ds
        (sql/format {:update :secrets
                     :set {:encrypted-value encrypted
                           :updated-at [:raw "datetime('now')"]}
                     :where [:and [:= :scope scope] [:= :name secret-name]]}))
      ;; Insert
      (jdbc/execute-one! ds
        (sql/format {:insert-into :secrets
                     :values [{:id (util/generate-id)
                               :scope scope
                               :name secret-name
                               :encrypted-value encrypted}]})))))

(defn get-secret
  "Get a decrypted secret value. Returns nil if not found."
  [ds config secret-name & {:keys [scope] :or {scope "global"}}]
  (let [row (jdbc/execute-one! ds
              (sql/format {:select [:encrypted-value]
                           :from :secrets
                           :where [:and [:= :scope scope] [:= :name secret-name]]})
              {:builder-fn rs/as-unqualified-kebab-maps})]
    (when row
      (let [key (get-master-key config)]
        (decrypt key (:encrypted-value row))))))

(defn list-secret-names
  "List secret names (never values) for a scope. Returns a vector of name strings."
  [ds & {:keys [scope] :or {scope "global"}}]
  (mapv :name
    (jdbc/execute! ds
      (sql/format {:select [:name]
                   :from :secrets
                   :where [:= :scope scope]
                   :order-by [[:name :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn delete-secret!
  "Delete a secret. Returns true if deleted, false if not found."
  [ds secret-name & {:keys [scope] :or {scope "global"}}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :secrets
                              :where [:and [:= :scope scope] [:= :name secret-name]]}))]
    (pos? (:next.jdbc/update-count result 0))))

(defn get-secrets-for-build
  "Get all secrets as a map of {name value} for a build.
   Merges global secrets with job-scoped secrets (job scope overrides global)."
  [ds config job-id]
  (let [key (get-master-key config)
        rows (jdbc/execute! ds
               (sql/format {:select [:scope :name :encrypted-value]
                            :from :secrets
                            :where [:or [:= :scope "global"] [:= :scope job-id]]
                            :order-by [[:scope :asc]]}) ;; global first, then job overrides
               {:builder-fn rs/as-unqualified-kebab-maps})]
    (reduce (fn [m row]
              (assoc m (:name row) (decrypt key (:encrypted-value row))))
            {}
            rows)))
