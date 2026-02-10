(ns chengis.engine.provenance-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.provenance-store :as provenance-store]
            [chengis.engine.provenance :as provenance])
  (:import [java.util Base64]))

(def test-db-path "/tmp/chengis-provenance-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; format-slsa-predicate tests
;; ---------------------------------------------------------------------------

(deftest format-slsa-predicate-complete-test
  (testing "format-slsa-predicate with complete build-result"
    (let [build-result {:build-id "b1"
                        :job-id "j1"
                        :build-number 42
                        :pipeline-source "(defpipeline my-pipe ...)"
                        :started-at "2025-01-15T10:00:00Z"
                        :completed-at "2025-01-15T10:05:00Z"
                        :git-info {:repo "https://github.com/org/repo"
                                   :branch "main"
                                   :commit "abc123"
                                   :parameters {:ref "refs/heads/main"}}}
          predicate (provenance/format-slsa-predicate build-result {})]
      (is (= "chengis/pipeline/v1" (get-in predicate [:buildDefinition :buildType])))
      (is (= "(defpipeline my-pipe ...)"
             (get-in predicate [:buildDefinition :externalParameters :pipeline])))
      (is (= {:ref "refs/heads/main"}
             (get-in predicate [:buildDefinition :externalParameters :parameters])))
      (is (= "b1" (get-in predicate [:buildDefinition :internalParameters :build-id])))
      (is (= "j1" (get-in predicate [:buildDefinition :internalParameters :job-id])))
      (is (= 42 (get-in predicate [:buildDefinition :internalParameters :build-number])))
      (is (= "chengis" (get-in predicate [:runDetails :builder :id])))
      (is (= "1.0" (get-in predicate [:runDetails :builder :version])))
      (is (= "b1" (get-in predicate [:runDetails :metadata :invocationId])))
      (is (= "2025-01-15T10:00:00Z" (get-in predicate [:runDetails :metadata :startedOn])))
      (is (= "2025-01-15T10:05:00Z" (get-in predicate [:runDetails :metadata :finishedOn])))
      (is (= [] (get-in predicate [:runDetails :byproducts]))))))

(deftest format-slsa-predicate-minimal-test
  (testing "format-slsa-predicate with minimal build-result (no git-info)"
    (let [build-result {:build-id "b2"
                        :job-id "j2"
                        :build-number 1
                        :started-at "2025-01-15T10:00:00Z"
                        :completed-at "2025-01-15T10:01:00Z"}
          predicate (provenance/format-slsa-predicate build-result {})]
      (is (= "chengis/pipeline/v1" (get-in predicate [:buildDefinition :buildType])))
      (is (nil? (get-in predicate [:buildDefinition :externalParameters :pipeline])))
      (is (= {} (get-in predicate [:buildDefinition :externalParameters :parameters])))
      (is (= "b2" (get-in predicate [:buildDefinition :internalParameters :build-id]))))))

;; ---------------------------------------------------------------------------
;; format-subjects tests
;; ---------------------------------------------------------------------------

(deftest format-subjects-multiple-test
  (testing "format-subjects with multiple artifacts"
    (let [artifacts [{:filename "app.jar" :sha256-hash "abc123"}
                     {:filename "app.tar.gz" :sha256-hash "def456"}
                     {:filename "docs.zip" :sha256-hash "ghi789"}]
          subjects (provenance/format-subjects artifacts)]
      (is (= 3 (count subjects)))
      (is (= "app.jar" (:name (first subjects))))
      (is (= "abc123" (get-in (first subjects) [:digest :sha256])))
      (is (= "app.tar.gz" (:name (second subjects))))
      (is (= "def456" (get-in (second subjects) [:digest :sha256]))))))

(deftest format-subjects-empty-test
  (testing "format-subjects with empty artifacts"
    (let [subjects (provenance/format-subjects [])]
      (is (empty? subjects)))))

(deftest format-subjects-missing-hash-test
  (testing "format-subjects with missing hash defaults to unknown"
    (let [artifacts [{:filename "app.jar"}]
          subjects (provenance/format-subjects artifacts)]
      (is (= 1 (count subjects)))
      (is (= "unknown" (get-in (first subjects) [:digest :sha256]))))))

;; ---------------------------------------------------------------------------
;; wrap-dsse-envelope tests
;; ---------------------------------------------------------------------------

(deftest wrap-dsse-envelope-test
  (testing "wrap-dsse-envelope structure validation"
    (let [payload-type "https://slsa.dev/provenance/v1"
          subject [{:name "app.jar" :digest {:sha256 "abc123"}}]
          predicate {:buildDefinition {:buildType "chengis/pipeline/v1"}}
          envelope (provenance/wrap-dsse-envelope payload-type subject predicate)]
      (is (= payload-type (:payloadType envelope)))
      (is (string? (:payload envelope)))
      (is (= [] (:signatures envelope)))
      ;; Verify payload is valid base64
      (let [decoder (Base64/getDecoder)
            decoded-bytes (.decode decoder (:payload envelope))
            decoded-str (String. decoded-bytes "UTF-8")
            decoded-json (json/read-str decoded-str :key-fn keyword)]
        (is (= payload-type (:_type decoded-json)))
        (is (= subject (:subject decoded-json)))
        (is (= predicate (:predicate decoded-json)))))))

;; ---------------------------------------------------------------------------
;; generate-provenance! tests
;; ---------------------------------------------------------------------------

(deftest generate-provenance-with-db-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "generate-provenance! persists attestation to DB"
      (let [system {:db ds
                    :config {:feature-flags {:slsa-provenance true}}}
            build-result {:build-id "build-prov-1"
                          :job-id "job-1"
                          :org-id "org-1"
                          :build-number 1
                          :pipeline-source "(defpipeline test-pipe ...)"
                          :started-at "2025-01-15T10:00:00Z"
                          :completed-at "2025-01-15T10:05:00Z"
                          :artifacts [{:filename "app.jar" :sha256-hash "aabbcc"}]
                          :git-info {:repo "https://github.com/org/repo"
                                     :branch "main"
                                     :commit "deadbeef"}}
            attestation (provenance/generate-provenance! system build-result)]
        (is (some? attestation))
        (is (= "build-prov-1" (:build-id attestation)))
        (is (= "org-1" (:org-id attestation)))
        (is (= "L1" (:slsa-level attestation)))
        ;; Verify it was persisted
        (let [db-attestation (provenance-store/get-attestation ds "build-prov-1")]
          (is (some? db-attestation))
          (is (= "build-prov-1" (:build-id db-attestation)))
          (is (some? (:predicate-json db-attestation)))
          (is (some? (:envelope-json db-attestation)))
          (is (= "https://github.com/org/repo" (:source-repo db-attestation)))
          (is (= "main" (:source-branch db-attestation)))
          (is (= "deadbeef" (:source-commit db-attestation))))))))

(deftest generate-provenance-feature-flag-disabled-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "generate-provenance! returns nil when feature flag disabled"
      (let [system {:db ds
                    :config {:feature-flags {:slsa-provenance false}}}
            build-result {:build-id "build-prov-disabled"
                          :job-id "job-1"
                          :build-number 1}
            result (provenance/generate-provenance! system build-result)]
        (is (nil? result))
        ;; Verify nothing was persisted
        (is (nil? (provenance-store/get-attestation ds "build-prov-disabled")))))))

(deftest generate-provenance-empty-artifacts-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "generate-provenance! handles empty artifacts"
      (let [system {:db ds
                    :config {:feature-flags {:slsa-provenance true}}}
            build-result {:build-id "build-prov-empty"
                          :job-id "job-1"
                          :org-id "org-1"
                          :build-number 1
                          :started-at "2025-01-15T10:00:00Z"
                          :completed-at "2025-01-15T10:01:00Z"
                          :artifacts []}
            attestation (provenance/generate-provenance! system build-result)]
        (is (some? attestation))
        ;; Subject JSON should be empty array
        (let [subjects (json/read-str (:subject-json attestation) :key-fn keyword)]
          (is (empty? subjects)))))))

;; ---------------------------------------------------------------------------
;; provenance-store CRUD tests
;; ---------------------------------------------------------------------------

(deftest provenance-store-create-get-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create and get attestation"
      (let [attestation (provenance-store/create-attestation! ds
                          {:build-id "build-crud-1"
                           :job-id "job-1"
                           :org-id "org-1"
                           :subject-json "[]"
                           :predicate-json "{}"
                           :envelope-json "{}"
                           :source-repo "https://github.com/org/repo"})
            fetched (provenance-store/get-attestation ds "build-crud-1")]
        (is (some? fetched))
        (is (= (:id attestation) (:id fetched)))
        (is (= "build-crud-1" (:build-id fetched)))
        (is (= "org-1" (:org-id fetched)))))))

(deftest provenance-store-list-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list attestations with org filter"
      (provenance-store/create-attestation! ds
        {:build-id "build-list-1" :job-id "j1" :org-id "org-a"
         :subject-json "[]" :predicate-json "{}" :envelope-json "{}"})
      (provenance-store/create-attestation! ds
        {:build-id "build-list-2" :job-id "j1" :org-id "org-b"
         :subject-json "[]" :predicate-json "{}" :envelope-json "{}"})
      (provenance-store/create-attestation! ds
        {:build-id "build-list-3" :job-id "j2" :org-id "org-a"
         :subject-json "[]" :predicate-json "{}" :envelope-json "{}"})
      ;; All attestations
      (let [all (provenance-store/list-attestations ds)]
        (is (= 3 (count all))))
      ;; Filtered by org
      (let [org-a (provenance-store/list-attestations ds :org-id "org-a")]
        (is (= 2 (count org-a)))
        (is (every? #(= "org-a" (:org-id %)) org-a)))
      ;; Filtered by org-id via get-attestation
      (let [fetched (provenance-store/get-attestation ds "build-list-1" :org-id "org-a")]
        (is (some? fetched)))
      (let [wrong-org (provenance-store/get-attestation ds "build-list-1" :org-id "org-b")]
        (is (nil? wrong-org))))))

(deftest provenance-store-delete-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "delete attestation with org check"
      (let [attestation (provenance-store/create-attestation! ds
                          {:build-id "build-del-1" :job-id "j1" :org-id "org-a"
                           :subject-json "[]" :predicate-json "{}" :envelope-json "{}"})]
        ;; Delete with wrong org should not delete
        (provenance-store/delete-attestation! ds (:id attestation) :org-id "org-b")
        (is (some? (provenance-store/get-attestation ds "build-del-1")))
        ;; Delete with correct org should work
        (provenance-store/delete-attestation! ds (:id attestation) :org-id "org-a")
        (is (nil? (provenance-store/get-attestation ds "build-del-1")))))))
