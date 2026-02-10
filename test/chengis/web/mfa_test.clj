(ns chengis.web.mfa-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.mfa :as mfa]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def test-db-path "/tmp/chengis-mfa-test.db")

;; Fixed 32-byte master key for tests
(def test-master-key (apply str (repeat 32 "a")))

(def test-config
  {:secrets {:master-key test-master-key}
   :feature-flags {:mfa-totp true}
   :mfa {:issuer "Chengis CI Test"}})

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Base32 encoding / decoding
;; ---------------------------------------------------------------------------

(deftest base32-roundtrip-test
  (testing "base32 encode and decode roundtrip"
    (let [original (byte-array [0x48 0x65 0x6C 0x6C 0x6F])  ;; "Hello"
          encoded (mfa/base32-encode original)
          decoded (mfa/base32-decode encoded)]
      (is (string? encoded))
      (is (= (seq original) (seq decoded)))))

  (testing "base32 encode known value"
    ;; "Hello" -> "JBSWY3DP" per RFC 4648
    (let [result (mfa/base32-encode (.getBytes "Hello" "UTF-8"))]
      (is (= "JBSWY3DP" result))))

  (testing "base32 decode is case-insensitive"
    (let [upper (mfa/base32-decode "JBSWY3DP")
          lower (mfa/base32-decode "jbswy3dp")]
      (is (= (seq upper) (seq lower))))))

;; ---------------------------------------------------------------------------
;; TOTP secret generation
;; ---------------------------------------------------------------------------

(deftest generate-totp-secret-test
  (testing "generate-totp-secret returns valid base32 string"
    (let [secret (mfa/generate-totp-secret)]
      (is (string? secret))
      (is (pos? (count secret)))
      ;; All chars should be valid base32
      (is (re-matches #"[A-Z2-7]+" secret))
      ;; Decoding should produce 20 bytes
      (is (= 20 (alength (mfa/base32-decode secret)))))))

;; ---------------------------------------------------------------------------
;; TOTP URI
;; ---------------------------------------------------------------------------

(deftest totp-uri-test
  (testing "totp-uri format"
    (let [uri (mfa/totp-uri "JBSWY3DPEHPK3PXP" "alice" "Chengis CI")]
      (is (str/starts-with? uri "otpauth://totp/"))
      (is (str/includes? uri "secret=JBSWY3DPEHPK3PXP"))
      (is (str/includes? uri "issuer=Chengis"))
      (is (str/includes? uri "algorithm=SHA1"))
      (is (str/includes? uri "digits=6"))
      (is (str/includes? uri "period=30")))))

;; ---------------------------------------------------------------------------
;; TOTP computation & verification
;; ---------------------------------------------------------------------------

(deftest compute-totp-test
  (testing "compute-totp produces 6-digit zero-padded string"
    (let [secret-bytes (mfa/base32-decode "JBSWY3DPEHPK3PXP")
          code (mfa/compute-totp secret-bytes 1)]
      (is (string? code))
      (is (= 6 (count code)))
      (is (re-matches #"\d{6}" code))))

  (testing "compute-totp is deterministic for same inputs"
    (let [secret-bytes (mfa/base32-decode "JBSWY3DPEHPK3PXP")]
      (is (= (mfa/compute-totp secret-bytes 12345)
             (mfa/compute-totp secret-bytes 12345)))))

  (testing "compute-totp differs for different time steps"
    (let [secret-bytes (mfa/base32-decode "JBSWY3DPEHPK3PXP")]
      (is (not= (mfa/compute-totp secret-bytes 1)
                (mfa/compute-totp secret-bytes 99999))))))

(deftest verify-totp-code-test
  (testing "verify-totp-code accepts correct code for current time"
    (let [secret-b32 "JBSWY3DPEHPK3PXP"
          secret-bytes (mfa/base32-decode secret-b32)
          time-step (quot (quot (System/currentTimeMillis) 1000) 30)
          code (mfa/compute-totp secret-bytes time-step)]
      (is (true? (mfa/verify-totp-code secret-b32 code)))))

  (testing "verify-totp-code rejects wrong code"
    (is (not (mfa/verify-totp-code "JBSWY3DPEHPK3PXP" "000000")))
    (is (not (mfa/verify-totp-code "JBSWY3DPEHPK3PXP" "999999"))))

  (testing "verify-totp-code rejects non-6-digit input"
    (is (not (mfa/verify-totp-code "JBSWY3DPEHPK3PXP" "12345")))
    (is (not (mfa/verify-totp-code "JBSWY3DPEHPK3PXP" "abcdef")))
    (is (not (mfa/verify-totp-code "JBSWY3DPEHPK3PXP" nil)))))

;; ---------------------------------------------------------------------------
;; Encryption roundtrip
;; ---------------------------------------------------------------------------

(deftest encrypt-decrypt-roundtrip-test
  (testing "encrypt-secret / decrypt-secret roundtrip"
    (let [plaintext "JBSWY3DPEHPK3PXP"
          encrypted (mfa/encrypt-secret plaintext test-master-key)
          decrypted (mfa/decrypt-secret encrypted test-master-key)]
      (is (string? encrypted))
      (is (not= plaintext encrypted))
      (is (= plaintext decrypted))))

  (testing "different encryptions produce different ciphertexts (random IV)"
    (let [plaintext "MYSECRET"
          e1 (mfa/encrypt-secret plaintext test-master-key)
          e2 (mfa/encrypt-secret plaintext test-master-key)]
      (is (not= e1 e2))
      (is (= plaintext (mfa/decrypt-secret e1 test-master-key)))
      (is (= plaintext (mfa/decrypt-secret e2 test-master-key))))))

;; ---------------------------------------------------------------------------
;; Store operations (DB-backed)
;; ---------------------------------------------------------------------------

(deftest enroll-totp-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "mfa-user"
                                           :password "password123"
                                           :role "developer"})
        user-id (:id user)]
    (testing "enroll-totp! creates enrollment and recovery codes in DB"
      (let [result (mfa/enroll-totp! ds user-id test-config)]
        (is (string? (:secret-b32 result)))
        (is (str/starts-with? (:uri result) "otpauth://totp/"))
        (is (= 8 (count (:recovery-codes result))))
        ;; Each recovery code matches XXXX-XXXX format
        (doseq [code (:recovery-codes result)]
          (is (re-matches #"[A-Z2-9]{4}-[A-Z2-9]{4}" code)))))

    (testing "enrollment exists in DB but is unverified"
      (let [enrollment (mfa/get-enrollment ds user-id)]
        (is (some? enrollment))
        (is (= 0 (:verified enrollment)))))))

(deftest totp-enrolled-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "enroll-check"
                                           :password "password123"
                                           :role "developer"})
        user-id (:id user)]
    (testing "totp-enrolled? returns false before enrollment"
      (is (false? (mfa/totp-enrolled? ds user-id))))

    (testing "totp-enrolled? returns false after enrollment but before confirmation"
      (mfa/enroll-totp! ds user-id test-config)
      (is (false? (mfa/totp-enrolled? ds user-id))))

    (testing "totp-enrolled? returns true after confirmation"
      (let [enrollment (mfa/get-enrollment ds user-id)
            master-key (get-in test-config [:secrets :master-key])
            secret-b32 (mfa/decrypt-secret (:secret-encrypted enrollment) master-key)
            secret-bytes (mfa/base32-decode secret-b32)
            time-step (quot (quot (System/currentTimeMillis) 1000) 30)
            code (mfa/compute-totp secret-bytes time-step)]
        (is (true? (mfa/confirm-totp! ds user-id code test-config)))
        (is (true? (mfa/totp-enrolled? ds user-id)))))))

(deftest confirm-totp-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "confirm-user"
                                           :password "password123"
                                           :role "developer"})
        user-id (:id user)
        result (mfa/enroll-totp! ds user-id test-config)]
    (testing "confirm-totp! with correct code sets verified=1"
      (let [secret-bytes (mfa/base32-decode (:secret-b32 result))
            time-step (quot (quot (System/currentTimeMillis) 1000) 30)
            code (mfa/compute-totp secret-bytes time-step)]
        (is (true? (mfa/confirm-totp! ds user-id code test-config)))
        (let [enrollment (mfa/get-enrollment ds user-id)]
          (is (= 1 (:verified enrollment))))))

    (testing "confirm-totp! with wrong code returns false"
      ;; Re-enroll to get a fresh unverified enrollment
      (mfa/enroll-totp! ds user-id test-config)
      (is (false? (mfa/confirm-totp! ds user-id "000000" test-config))))))

(deftest check-totp-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "check-user"
                                           :password "password123"
                                           :role "developer"})
        user-id (:id user)
        result (mfa/enroll-totp! ds user-id test-config)
        secret-bytes (mfa/base32-decode (:secret-b32 result))
        time-step (quot (quot (System/currentTimeMillis) 1000) 30)
        code (mfa/compute-totp secret-bytes time-step)]
    ;; Confirm enrollment first
    (mfa/confirm-totp! ds user-id code test-config)

    (testing "check-totp! works after confirmation"
      (let [new-code (mfa/compute-totp secret-bytes
                       (quot (quot (System/currentTimeMillis) 1000) 30))]
        (is (true? (mfa/check-totp! ds user-id new-code test-config)))))

    (testing "check-totp! rejects invalid code"
      (is (false? (mfa/check-totp! ds user-id "000000" test-config))))))

(deftest recovery-code-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "recovery-user"
                                           :password "password123"
                                           :role "developer"})
        user-id (:id user)
        result (mfa/enroll-totp! ds user-id test-config)
        codes (:recovery-codes result)]
    ;; Confirm enrollment
    (let [secret-bytes (mfa/base32-decode (:secret-b32 result))
          time-step (quot (quot (System/currentTimeMillis) 1000) 30)
          code (mfa/compute-totp secret-bytes time-step)]
      (mfa/confirm-totp! ds user-id code test-config))

    (testing "use-recovery-code! marks code as used"
      (is (true? (mfa/use-recovery-code! ds user-id (first codes)))))

    (testing "use-recovery-code! rejects already-used code"
      (is (false? (mfa/use-recovery-code! ds user-id (first codes)))))

    (testing "use-recovery-code! rejects invalid code"
      (is (false? (mfa/use-recovery-code! ds user-id "INVALID-CODE"))))

    (testing "other recovery codes still work"
      (is (true? (mfa/use-recovery-code! ds user-id (second codes)))))))

(deftest disable-totp-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "disable-user"
                                           :password "password123"
                                           :role "developer"})
        user-id (:id user)
        result (mfa/enroll-totp! ds user-id test-config)]
    ;; Confirm enrollment
    (let [secret-bytes (mfa/base32-decode (:secret-b32 result))
          time-step (quot (quot (System/currentTimeMillis) 1000) 30)
          code (mfa/compute-totp secret-bytes time-step)]
      (mfa/confirm-totp! ds user-id code test-config))

    (testing "disable-totp! removes enrollment and codes"
      (is (true? (mfa/totp-enrolled? ds user-id)))
      (is (true? (mfa/disable-totp! ds user-id)))
      (is (false? (mfa/totp-enrolled? ds user-id)))
      (is (nil? (mfa/get-enrollment ds user-id))))

    (testing "disable-totp! returns false when no enrollment"
      (is (false? (mfa/disable-totp! ds user-id))))))

(deftest feature-flag-gating-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "flag-user"
                                           :password "password123"
                                           :role "developer"})
        user-id (:id user)
        disabled-config (assoc-in test-config [:feature-flags :mfa-totp] false)]
    (testing "enroll-totp! throws when feature flag is disabled"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Feature not enabled"
                            (mfa/enroll-totp! ds user-id disabled-config))))))
