(ns chengis.engine.signing-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.signature-store :as signature-store]
            [chengis.engine.signing :as signing]))

(def test-db-path "/tmp/chengis-signing-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  ;; Cleanup
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (let [artifact-file (io/file "/tmp/test-artifact.txt")]
    (when (.exists artifact-file) (.delete artifact-file)))
  (let [sig-file (io/file "/tmp/test-artifact.txt.sig")]
    (when (.exists sig-file) (.delete sig-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Command builder tests
;; ---------------------------------------------------------------------------

(deftest build-cosign-command-test
  (testing "build-cosign-command produces correct format"
    (let [cmd (signing/build-cosign-command "cosign.key" "/tmp/artifact.tar" "/tmp/artifact.tar.sig")]
      (is (= "cosign sign-blob --yes --key cosign.key --output-signature /tmp/artifact.tar.sig /tmp/artifact.tar"
             cmd)))))

(deftest build-gpg-command-test
  (testing "build-gpg-command produces correct format"
    (let [cmd (signing/build-gpg-command "alice@example.com" "/tmp/artifact.tar" "/tmp/artifact.tar.sig")]
      (is (= "gpg --batch --yes --detach-sign --armor --local-user alice@example.com --output /tmp/artifact.tar.sig /tmp/artifact.tar"
             cmd)))))

;; ---------------------------------------------------------------------------
;; Digest tests
;; ---------------------------------------------------------------------------

(deftest compute-digest-known-string-test
  (testing "compute-digest returns correct SHA-256 for known content"
    (spit "/tmp/test-artifact.txt" "test content")
    (let [digest (signing/compute-digest "/tmp/test-artifact.txt")]
      (is (some? digest))
      (is (= 64 (count digest)))
      ;; SHA-256 of "test content" is known
      (is (= "6ae8a75555209fd6c44157c0aed8016e763ff435a19cf186f76863140143ff72"
             digest)))))

(deftest compute-digest-nonexistent-file-test
  (testing "compute-digest returns nil for nonexistent file"
    (is (nil? (signing/compute-digest "/tmp/nonexistent-file-12345.txt")))))

;; ---------------------------------------------------------------------------
;; sign-single-artifact! tests
;; ---------------------------------------------------------------------------

(deftest sign-single-artifact-cosign-success-test
  (testing "sign-single-artifact! with mocked cosign success"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds :config {:signing {:tool "cosign" :key-ref "cosign.key" :timeout 5000}}}
          artifact {:id "art-1" :build-id "build-1" :job-id "job-1"
                    :org-id "org-1" :filename "app.tar" :path "/tmp/test-artifact.txt"}
          _ (spit "/tmp/test-artifact.txt" "test content")]
      ;; Mock by creating the .sig file and using echo which exits 0
      (with-redefs [chengis.engine.process/execute-command
                    (fn [opts]
                      ;; Simulate cosign writing the sig file
                      (spit "/tmp/test-artifact.txt.sig" "MOCK-SIGNATURE-DATA")
                      {:exit-code 0 :stdout "Signature written" :stderr "" :timed-out? false})]
        (let [result (signing/sign-single-artifact! system artifact
                       {:tool "cosign" :key-ref "cosign.key" :timeout 5000})]
          (is (some? result))
          (is (= "cosign" (:signer result)))
          (is (= "cosign.key" (:key-reference result)))
          (is (= "build-1" (:build-id result)))
          ;; Verify stored in DB
          (let [sigs (signature-store/get-build-signatures ds "build-1")]
            (is (= 1 (count sigs)))
            (is (= "cosign" (:signer (first sigs))))))))))

(deftest sign-single-artifact-no-key-ref-test
  (testing "sign-single-artifact! skips when no key-ref configured"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds :config {:signing {:tool "cosign"}}}
          artifact {:id "art-2" :build-id "build-2" :job-id "job-1"
                    :org-id "org-1" :filename "app.tar" :path "/tmp/app.tar"}]
      (let [result (signing/sign-single-artifact! system artifact
                     {:tool "cosign"})]
        (is (nil? result))))))

(deftest sign-single-artifact-tool-not-found-test
  (testing "sign-single-artifact! handles tool not found (exit 127)"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds :config {:signing {:tool "cosign" :key-ref "cosign.key"}}}
          artifact {:id "art-3" :build-id "build-3" :job-id "job-1"
                    :org-id "org-1" :filename "app.tar" :path "/tmp/test-artifact.txt"}
          _ (spit "/tmp/test-artifact.txt" "test content")]
      (with-redefs [chengis.engine.process/execute-command
                    (fn [_]
                      {:exit-code 127 :stdout "" :stderr "cosign: command not found" :timed-out? false})]
        (let [result (signing/sign-single-artifact! system artifact
                       {:tool "cosign" :key-ref "cosign.key"})]
          (is (nil? result)))))))

;; ---------------------------------------------------------------------------
;; sign-artifacts! tests
;; ---------------------------------------------------------------------------

(deftest sign-artifacts-multiple-test
  (testing "sign-artifacts! signs multiple artifacts"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds
                  :config {:feature-flags {:artifact-signing true}
                           :signing {:tool "cosign" :key-ref "cosign.key" :timeout 5000}}}
          build-result {:artifacts [{:id "art-a" :build-id "build-m" :job-id "job-1"
                                     :org-id "org-1" :filename "app.tar" :path "/tmp/test-artifact.txt"}
                                    {:id "art-b" :build-id "build-m" :job-id "job-1"
                                     :org-id "org-1" :filename "lib.jar" :path "/tmp/test-artifact.txt"}]}
          _ (spit "/tmp/test-artifact.txt" "test content")]
      (with-redefs [chengis.engine.process/execute-command
                    (fn [opts]
                      (spit "/tmp/test-artifact.txt.sig" "MOCK-SIG")
                      {:exit-code 0 :stdout "" :stderr "" :timed-out? false})]
        (let [results (signing/sign-artifacts! system build-result)]
          (is (= 2 (count results)))
          (is (every? #(= "cosign" (:signer %)) results)))))))

(deftest sign-artifacts-feature-flag-disabled-test
  (testing "sign-artifacts! returns empty when feature flag disabled"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds
                  :config {:feature-flags {:artifact-signing false}
                           :signing {:tool "cosign" :key-ref "cosign.key"}}}
          build-result {:artifacts [{:id "art-x" :build-id "build-x" :job-id "job-1"
                                     :filename "app.tar" :path "/tmp/app.tar"}]}]
      (let [results (signing/sign-artifacts! system build-result)]
        (is (empty? results))))))

;; ---------------------------------------------------------------------------
;; verify-signature! tests
;; ---------------------------------------------------------------------------

(deftest verify-signature-success-test
  (testing "verify-signature! marks as verified on success"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds :config {:signing {:timeout 5000}}}
          sig (signature-store/create-signature! ds
                {:build-id "build-v1" :job-id "job-1" :org-id "org-1"
                 :signer "cosign" :key-reference "cosign.key"
                 :signature-value "FAKESIG" :target-digest "abc123"})]
      (with-redefs [chengis.engine.process/execute-command
                    (fn [_]
                      {:exit-code 0 :stdout "Verified OK" :stderr "" :timed-out? false})]
        (let [result (signing/verify-signature! system
                       (assoc sig :artifact-path "/tmp/test-artifact.txt"))]
          (is (true? (:verified? result)))
          ;; Check DB was updated
          (let [sigs (signature-store/get-build-signatures ds "build-v1")]
            (is (= 1 (:verified (first sigs))))))))))

(deftest verify-signature-failure-test
  (testing "verify-signature! returns error on verification failure"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds :config {:signing {:timeout 5000}}}
          sig (signature-store/create-signature! ds
                {:build-id "build-v2" :job-id "job-1" :org-id "org-1"
                 :signer "cosign" :key-reference "cosign.key"
                 :signature-value "BADSIG" :target-digest "abc123"})]
      (with-redefs [chengis.engine.process/execute-command
                    (fn [_]
                      {:exit-code 1 :stdout "" :stderr "verification failed" :timed-out? false})]
        (let [result (signing/verify-signature! system
                       (assoc sig :artifact-path "/tmp/test-artifact.txt"))]
          (is (false? (:verified? result)))
          (is (some? (:error result)))
          ;; DB should still show unverified
          (let [sigs (signature-store/get-build-signatures ds "build-v2")]
            (is (= 0 (:verified (first sigs))))))))))

;; ---------------------------------------------------------------------------
;; signature-store CRUD tests
;; ---------------------------------------------------------------------------

(deftest signature-store-crud-test
  (testing "signature store create, get, list, verify, cleanup"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Create
      (let [sig (signature-store/create-signature! ds
                  {:build-id "b1" :job-id "j1" :org-id "org-1"
                   :signer "gpg" :key-reference "alice@example.com"
                   :signature-value "SIG123" :target-digest "hash1"
                   :artifact-id "art-1"})]
        (is (some? (:id sig)))
        (is (= 0 (:verified sig))))

      ;; Create another for same build
      (signature-store/create-signature! ds
        {:build-id "b1" :job-id "j1" :org-id "org-1"
         :signer "cosign" :key-reference "cosign.key"
         :signature-value "SIG456" :target-digest "hash2"
         :artifact-id "art-2"})

      ;; Get build signatures
      (let [sigs (signature-store/get-build-signatures ds "b1")]
        (is (= 2 (count sigs))))

      ;; List with org filter
      (let [sigs (signature-store/list-signatures ds :org-id "org-1")]
        (is (= 2 (count sigs))))

      ;; List with verified filter
      (let [sigs (signature-store/list-signatures ds :verified 0)]
        (is (= 2 (count sigs))))

      ;; Verify one signature
      (let [sigs (signature-store/get-build-signatures ds "b1")
            first-id (:id (first sigs))]
        (signature-store/verify-signature! ds first-id)
        (let [updated-sigs (signature-store/list-signatures ds :verified 1)]
          (is (= 1 (count updated-sigs)))
          (is (some? (:verified-at (first updated-sigs))))))

      ;; Cleanup (with -1 days retention = cutoff 1 day in the future, should delete all)
      (let [deleted (signature-store/cleanup-old-signatures! ds -1)]
        (is (= 2 deleted))
        (is (empty? (signature-store/list-signatures ds)))))))

;; ---------------------------------------------------------------------------
;; Phase 1 mutation remediation: verify-signature! returns false on error
;; ---------------------------------------------------------------------------

(deftest verify-unknown-tool-returns-false-test
  (testing "verify-signature! returns :verified? false for unknown tool"
    (let [result (signing/verify-signature!
                   {:config {:signing {:timeout 5000}}}
                   {:signer "nonexistent-tool"
                    :key-reference "some-key"
                    :signature-value "FAKESIG"
                    :artifact-path "/tmp/fake-artifact.txt"})]
      (is (false? (:verified? result))
          ":verified? must be false for unknown signing tool")
      (is (string? (:error result))))))
