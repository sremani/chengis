(ns chengis.web.mfa
  "Multi-factor authentication via TOTP (Time-based One-Time Password).
   Supports enrollment with QR code, verification, and recovery codes.
   Feature-flag gated via :mfa-totp.

   TOTP is implemented using standard Java crypto (RFC 6238):
   1. Secret: 20-byte random, base32-encoded
   2. TOTP = HOTP(secret, time_step) where time_step = floor(unix_seconds / 30)
   3. HOTP = truncate(HMAC-SHA1(secret, counter))"
  (:require [chengis.util :as util]
            [chengis.feature-flags :as feature-flags]
            [buddy.hashers :as hashers]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [taoensso.timbre :as log])
  (:import [java.security SecureRandom]
           [java.util Base64]
           [javax.crypto Cipher Mac]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private gcm-tag-bits 128)
(def ^:private iv-bytes 12)
(def ^:private totp-digits 6)
(def ^:private totp-period 30)
(def ^:private totp-secret-bytes 20)
(def ^:private recovery-code-count 8)

;; ---------------------------------------------------------------------------
;; Base32 encoding/decoding (RFC 4648)
;; ---------------------------------------------------------------------------

(def ^:private base32-alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

(defn base32-encode
  "Encode a byte array to a base32 string (RFC 4648, no padding)."
  [^bytes bs]
  (let [sb (StringBuilder.)
        len (alength bs)]
    (loop [i 0, buffer 0, bits-left 0]
      (cond
        ;; Emit a character when we have >= 5 bits
        (>= bits-left 5)
        (let [idx (bit-and (bit-shift-right buffer (- bits-left 5)) 0x1F)]
          (.append sb (.charAt base32-alphabet idx))
          (recur i buffer (- bits-left 5)))

        ;; Load more bytes if available
        (< i len)
        (recur (inc i)
               (bit-or (bit-shift-left buffer 8) (bit-and (aget bs i) 0xFF))
               (+ bits-left 8))

        ;; Flush remaining bits (< 5 bits) by left-padding to 5
        (pos? bits-left)
        (let [idx (bit-and (bit-shift-left buffer (- 5 bits-left)) 0x1F)]
          (.append sb (.charAt base32-alphabet idx))
          (.toString sb))

        ;; Done
        :else
        (.toString sb)))))

(defn base32-decode
  "Decode a base32 string (RFC 4648) to a byte array. Case-insensitive."
  [^String s]
  (let [s (str/upper-case (str/replace s #"=" ""))
        out (java.io.ByteArrayOutputStream.)]
    (loop [i 0
           buffer 0
           bits-left 0]
      (if (>= i (.length s))
        (.toByteArray out)
        (let [c (.charAt s i)
              val (.indexOf base32-alphabet (int c))]
          (if (neg? val)
            (recur (inc i) buffer bits-left)
            (let [buffer (bit-or (bit-shift-left buffer 5) val)
                  bits-left (+ bits-left 5)]
              (if (>= bits-left 8)
                (let [byte-val (bit-and (bit-shift-right buffer (- bits-left 8)) 0xFF)]
                  (.write out byte-val)
                  (recur (inc i) buffer (- bits-left 8)))
                (recur (inc i) buffer bits-left)))))))))

;; ---------------------------------------------------------------------------
;; TOTP core (RFC 6238 / RFC 4226)
;; ---------------------------------------------------------------------------

(defn generate-totp-secret
  "Generate a 20-byte random TOTP secret. Returns base32-encoded string."
  []
  (let [bytes (byte-array totp-secret-bytes)]
    (.nextBytes (SecureRandom.) bytes)
    (base32-encode bytes)))

(defn totp-uri
  "Build an otpauth:// URI for QR code provisioning.
   Format: otpauth://totp/{issuer}:{username}?secret={secret}&issuer={issuer}&algorithm=SHA1&digits=6&period=30"
  [secret username issuer]
  (let [encode #(java.net.URLEncoder/encode (str %) "UTF-8")]
    (str "otpauth://totp/" (encode issuer) ":" (encode username)
         "?secret=" (encode secret)
         "&issuer=" (encode issuer)
         "&algorithm=SHA1"
         "&digits=" totp-digits
         "&period=" totp-period)))

(defn compute-totp
  "Compute a TOTP value using HMAC-SHA1 for the given time step.
   Returns a zero-padded 6-digit string."
  [^bytes secret-bytes time-step]
  (let [;; Convert time-step to 8-byte big-endian
        msg (byte-array 8)
        _ (doseq [i (range 7 -1 -1)]
            (aset msg i (unchecked-byte (bit-and time-step 0xFF)))
            ;; Shift is done via the loop index — we use the original time-step shifted
            )
        ;; Actually fill the 8-byte counter properly
        msg (let [ba (byte-array 8)]
              (loop [ts time-step idx 7]
                (when (>= idx 0)
                  (aset ba idx (unchecked-byte (bit-and ts 0xFF)))
                  (recur (unsigned-bit-shift-right ts 8) (dec idx))))
              ba)
        ;; HMAC-SHA1
        mac (Mac/getInstance "HmacSHA1")
        key-spec (SecretKeySpec. secret-bytes "HmacSHA1")
        _ (.init mac key-spec)
        hash (.doFinal mac msg)
        ;; Dynamic truncation (RFC 4226 section 5.4)
        offset (bit-and (aget hash (dec (alength hash))) 0x0F)
        code (bit-and
               (bit-or
                 (bit-shift-left (bit-and (aget hash offset) 0x7F) 24)
                 (bit-shift-left (bit-and (aget hash (+ offset 1)) 0xFF) 16)
                 (bit-shift-left (bit-and (aget hash (+ offset 2)) 0xFF) 8)
                 (bit-and (aget hash (+ offset 3)) 0xFF))
               0x7FFFFFFF)
        otp (mod code (long (Math/pow 10 totp-digits)))]
    (format (str "%0" totp-digits "d") otp)))

(defn- current-time-step
  "Return the current TOTP time step (floor(unix-seconds / 30))."
  []
  (quot (quot (System/currentTimeMillis) 1000) totp-period))

(defn verify-totp-code
  "Verify a 6-digit TOTP code against the given base32-encoded secret.
   Allows +/-1 time step window for clock skew. Returns boolean."
  [secret-b32 code]
  (when (and (string? code) (re-matches #"\d{6}" code))
    (let [secret-bytes (base32-decode secret-b32)
          step (current-time-step)]
      (boolean
        (some #(= code (compute-totp secret-bytes %))
              [(dec step) step (inc step)])))))

;; ---------------------------------------------------------------------------
;; Encryption helpers (AES-256-GCM) — same approach as secret_store.clj
;; ---------------------------------------------------------------------------

(defn- derive-key
  "Derive a 256-bit AES key from a master key string using SHA-256."
  [master-key-str]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        hash (.digest digest (.getBytes ^String master-key-str "UTF-8"))]
    (SecretKeySpec. hash "AES")))

(defn- get-master-key
  "Get the AES master key from config or environment."
  [config]
  (let [explicit-key (or (get-in config [:secrets :master-key])
                         (System/getenv "CHENGIS_SECRET_KEY"))
        key-str (or explicit-key "chengis-dev-secret-key-change-me")]
    (when-not explicit-key
      (log/warn "SECURITY WARNING: Using default secret master key for TOTP."))
    (derive-key key-str)))

(defn encrypt-secret
  "Encrypt plaintext using AES-256-GCM with the given master key string.
   Returns Base64-encoded string of (IV + ciphertext + tag)."
  [^String plaintext ^String master-key]
  (let [key (derive-key master-key)
        iv (byte-array iv-bytes)
        _ (.nextBytes (SecureRandom.) iv)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")
        spec (GCMParameterSpec. gcm-tag-bits iv)]
    (.init cipher Cipher/ENCRYPT_MODE key spec)
    (let [ciphertext (.doFinal cipher (.getBytes plaintext "UTF-8"))
          combined (byte-array (+ iv-bytes (alength ciphertext)))]
      (System/arraycopy iv 0 combined 0 iv-bytes)
      (System/arraycopy ciphertext 0 combined iv-bytes (alength ciphertext))
      (.encodeToString (Base64/getEncoder) combined))))

(defn decrypt-secret
  "Decrypt a Base64-encoded AES-256-GCM string with the given master key string.
   Returns plaintext string."
  [^String encrypted ^String master-key]
  (let [key (derive-key master-key)
        combined (.decode (Base64/getDecoder) encrypted)
        iv (byte-array iv-bytes)
        ciphertext (byte-array (- (alength combined) iv-bytes))]
    (System/arraycopy combined 0 iv 0 iv-bytes)
    (System/arraycopy combined iv-bytes ciphertext 0 (alength ciphertext))
    (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
          spec (GCMParameterSpec. gcm-tag-bits iv)]
      (.init cipher Cipher/DECRYPT_MODE key spec)
      (String. (.doFinal cipher ciphertext) "UTF-8"))))

(defn- resolve-master-key
  "Resolve the master key string from config."
  [config]
  (or (get-in config [:secrets :master-key])
      (System/getenv "CHENGIS_SECRET_KEY")
      "chengis-dev-secret-key-change-me"))

;; ---------------------------------------------------------------------------
;; Recovery codes
;; ---------------------------------------------------------------------------

(defn- generate-recovery-code
  "Generate a single recovery code in XXXX-XXXX format (alphanumeric uppercase)."
  []
  (let [chars "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  ;; Exclude ambiguous: I, O, 0, 1
        rng (SecureRandom.)
        part (fn []
               (apply str (repeatedly 4 #(nth chars (.nextInt rng (count chars))))))]
    (str (part) "-" (part))))

(defn- generate-recovery-codes
  "Generate n unique recovery codes."
  [n]
  (vec (distinct (take n (repeatedly generate-recovery-code)))))

;; ---------------------------------------------------------------------------
;; Store operations
;; ---------------------------------------------------------------------------

(defn totp-enrolled?
  "Check if a user has a verified TOTP enrollment. Returns boolean."
  [ds user-id]
  (boolean
    (jdbc/execute-one! ds
      (sql/format {:select [:id]
                   :from :totp_enrollments
                   :where [:and [:= :user-id user-id] [:= :verified 1]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-enrollment
  "Get the TOTP enrollment record for a user. Returns map or nil."
  [ds user-id]
  (jdbc/execute-one! ds
    (sql/format {:select [:id :user-id :secret-encrypted :algorithm
                          :digits :period :verified :created-at]
                 :from :totp_enrollments
                 :where [:= :user-id user-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn enroll-totp!
  "Begin TOTP enrollment for a user:
   1. Generate a random TOTP secret
   2. Encrypt it with the master key
   3. Store in totp_enrollments (unverified)
   4. Generate 8 recovery codes, store bcrypt hashes
   Returns {:secret-b32, :uri, :recovery-codes} (plaintext, shown once).
   Throws if feature flag :mfa-totp is not enabled."
  [ds user-id config]
  (feature-flags/require-flag! config :mfa-totp)
  (let [master-key (resolve-master-key config)
        secret-b32 (generate-totp-secret)
        encrypted (encrypt-secret secret-b32 master-key)
        enrollment-id (util/generate-id)
        uri (totp-uri secret-b32 (or (get-in config [:mfa :username]) user-id)
                      (get-in config [:mfa :issuer] "Chengis CI"))
        recovery-codes (generate-recovery-codes recovery-code-count)]
    ;; Clean up any existing enrollment (re-enrollment)
    (jdbc/with-transaction [tx ds]
      (jdbc/execute-one! tx
        (sql/format {:delete-from :totp_recovery_codes
                     :where [:= :user-id user-id]}))
      (jdbc/execute-one! tx
        (sql/format {:delete-from :totp_enrollments
                     :where [:= :user-id user-id]}))
      ;; Insert new enrollment
      (jdbc/execute-one! tx
        (sql/format {:insert-into :totp_enrollments
                     :values [{:id enrollment-id
                               :user-id user-id
                               :secret-encrypted encrypted
                               :algorithm "SHA1"
                               :digits totp-digits
                               :period totp-period
                               :verified 0}]}))
      ;; Insert recovery codes (hashed)
      (doseq [code recovery-codes]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :totp_recovery_codes
                       :values [{:id (util/generate-id)
                                 :user-id user-id
                                 :code-hash (hashers/derive code {:alg :bcrypt+sha512})
                                 :used 0}]}))))
    (log/info "TOTP enrollment started for user:" user-id)
    {:secret-b32 secret-b32
     :uri uri
     :recovery-codes recovery-codes}))

(defn confirm-totp!
  "Confirm TOTP enrollment by verifying a code.
   Decrypts the stored secret, verifies the code, and marks verified=1.
   Returns true if confirmed, false if code is invalid or no enrollment."
  [ds user-id code config]
  (if-let [enrollment (get-enrollment ds user-id)]
    (let [master-key (resolve-master-key config)
          secret-b32 (decrypt-secret (:secret-encrypted enrollment) master-key)]
      (if (verify-totp-code secret-b32 code)
        (do
          (jdbc/execute-one! ds
            (sql/format {:update :totp_enrollments
                         :set {:verified 1}
                         :where [:= :user-id user-id]}))
          (log/info "TOTP enrollment confirmed for user:" user-id)
          true)
        (do
          (log/warn "TOTP confirmation failed — invalid code for user:" user-id)
          false)))
    (do
      (log/warn "TOTP confirmation failed — no enrollment for user:" user-id)
      false)))

(defn check-totp!
  "Verify a TOTP code for login. Only works if enrollment is verified.
   Decrypts the stored secret and verifies the code.
   Returns true if valid, false otherwise."
  [ds user-id code config]
  (if-let [enrollment (get-enrollment ds user-id)]
    (if (= 1 (:verified enrollment))
      (let [master-key (resolve-master-key config)
            secret-b32 (decrypt-secret (:secret-encrypted enrollment) master-key)]
        (verify-totp-code secret-b32 code))
      false)
    false))

(defn use-recovery-code!
  "Use a recovery code for login. Checks all unused codes for the user,
   verifies the plaintext code against each hash, and marks the first
   matching one as used. Returns true if a valid unused code was found."
  [ds user-id code]
  (let [codes (jdbc/execute! ds
                (sql/format {:select [:id :code-hash]
                             :from :totp_recovery_codes
                             :where [:and [:= :user-id user-id] [:= :used 0]]})
                {:builder-fn rs/as-unqualified-kebab-maps})]
    (if-let [matching (first (filter #(hashers/check code (:code-hash %)) codes))]
      (do
        (jdbc/execute-one! ds
          (sql/format {:update :totp_recovery_codes
                       :set {:used 1}
                       :where [:= :id (:id matching)]}))
        (log/info "Recovery code used for user:" user-id)
        true)
      (do
        (log/warn "Invalid recovery code attempt for user:" user-id)
        false))))

(defn disable-totp!
  "Disable TOTP for a user. Deletes enrollment and all recovery codes.
   Returns true if enrollment existed and was deleted, false otherwise."
  [ds user-id]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute-one! tx
      (sql/format {:delete-from :totp_recovery_codes
                   :where [:= :user-id user-id]}))
    (let [result (jdbc/execute-one! tx
                   (sql/format {:delete-from :totp_enrollments
                                :where [:= :user-id user-id]}))]
      (let [deleted? (pos? (:next.jdbc/update-count result 0))]
        (when deleted?
          (log/info "TOTP disabled for user:" user-id))
        deleted?))))
