(ns chengis.engine.sbom-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.sbom-store :as sbom-store]
            [chengis.engine.sbom :as sbom]
            [chengis.engine.process :as process]))

(def test-db-path "/tmp/chengis-sbom-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; detect-sbom-targets tests
;; ---------------------------------------------------------------------------

(deftest detect-sbom-targets-with-workspace-test
  (testing "detect-sbom-targets with workspace returns workspace as target"
    (let [build-result {:workspace "/tmp/build-ws"}
          targets (sbom/detect-sbom-targets build-result)]
      (is (seq targets))
      (is (some #(= "/tmp/build-ws" %) targets)))))

(deftest detect-sbom-targets-no-workspace-test
  (testing "detect-sbom-targets with no workspace returns empty"
    (let [build-result {}
          targets (sbom/detect-sbom-targets build-result)]
      (is (empty? targets)))))

(deftest detect-sbom-targets-with-docker-test
  (testing "detect-sbom-targets with docker images in pipeline-source"
    (let [build-result {:pipeline-source "docker.io/myorg/myapp:latest"
                        :workspace "/tmp/ws"}
          targets (sbom/detect-sbom-targets build-result)]
      (is (>= (count targets) 1)))))

;; ---------------------------------------------------------------------------
;; parse-sbom-output tests
;; ---------------------------------------------------------------------------

(deftest parse-sbom-output-valid-cyclonedx-test
  (testing "parse-sbom-output with valid CycloneDX JSON"
    (let [sbom-json (json/write-str {:bomFormat "CycloneDX"
                                     :specVersion "1.5"
                                     :components [{:name "a" :version "1.0"}
                                                  {:name "b" :version "2.0"}]})
          result (sbom/parse-sbom-output sbom-json "cyclonedx")]
      (is (some? result))
      (is (= 2 (:component-count result)))
      (is (= "1.5" (:version result)))
      (is (= 2 (count (:components result)))))))

(deftest parse-sbom-output-invalid-json-test
  (testing "parse-sbom-output with invalid JSON returns nil"
    (let [result (sbom/parse-sbom-output "not valid json" "cyclonedx")]
      (is (nil? result)))))

(deftest parse-sbom-output-empty-components-test
  (testing "parse-sbom-output with empty components"
    (let [sbom-json (json/write-str {:bomFormat "CycloneDX"
                                     :specVersion "1.4"
                                     :components []})
          result (sbom/parse-sbom-output sbom-json "cyclonedx")]
      (is (some? result))
      (is (= 0 (:component-count result))))))

;; ---------------------------------------------------------------------------
;; compute-content-hash tests
;; ---------------------------------------------------------------------------

(deftest compute-content-hash-determinism-test
  (testing "compute-content-hash is deterministic"
    (let [content "hello world"
          hash1 (sbom/compute-content-hash content)
          hash2 (sbom/compute-content-hash content)]
      (is (= hash1 hash2))
      (is (= 64 (count hash1)))
      (is (re-matches #"[0-9a-f]+" hash1)))))

(deftest compute-content-hash-different-inputs-test
  (testing "compute-content-hash produces different hashes for different inputs"
    (let [hash1 (sbom/compute-content-hash "hello")
          hash2 (sbom/compute-content-hash "world")]
      (is (not= hash1 hash2)))))

;; ---------------------------------------------------------------------------
;; generate-sbom! tests
;; ---------------------------------------------------------------------------

(deftest generate-sbom-with-mocked-process-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "generate-sbom! with mocked process returns SBOM records"
      (let [system {:db ds
                    :config {:feature-flags {:sbom-generation true}
                             :sbom {:tool "syft" :format "cyclonedx" :timeout 60000}}}
            build-result {:build-id "build-sbom-1"
                          :job-id "job-1"
                          :org-id "org-1"
                          :workspace "/tmp/test-ws"}
            sbom-output (json/write-str {:bomFormat "CycloneDX"
                                         :specVersion "1.5"
                                         :components [{:name "lib-a" :version "1.0"}
                                                      {:name "lib-b" :version "2.0"}
                                                      {:name "lib-c" :version "3.0"}]})]
        (with-redefs [process/execute-command (fn [_opts]
                                                {:exit-code 0
                                                 :stdout sbom-output
                                                 :stderr ""
                                                 :timed-out? false})]
          (let [results (sbom/generate-sbom! system build-result)]
            (is (some? results))
            (is (= 1 (count results)))
            ;; Verify persisted in DB
            (let [db-sboms (sbom-store/get-build-sboms ds "build-sbom-1")]
              (is (= 1 (count db-sboms)))
              (is (= "cyclonedx" (:sbom-format (first db-sboms))))
              (is (= 3 (:component-count (first db-sboms))))
              (is (some? (:content-hash (first db-sboms)))))))))))

(deftest generate-sbom-tool-not-found-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "generate-sbom! with tool not found (exit-code 127) returns nil"
      (let [system {:db ds
                    :config {:feature-flags {:sbom-generation true}
                             :sbom {:tool "syft"}}}
            build-result {:build-id "build-sbom-nf"
                          :job-id "job-1"
                          :workspace "/tmp/test-ws"}]
        (with-redefs [process/execute-command (fn [_opts]
                                                {:exit-code 127
                                                 :stdout ""
                                                 :stderr "command not found"
                                                 :timed-out? false})]
          (let [results (sbom/generate-sbom! system build-result)]
            (is (nil? results))))))))

(deftest generate-sbom-feature-flag-disabled-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "generate-sbom! returns nil when feature flag disabled"
      (let [system {:db ds
                    :config {:feature-flags {:sbom-generation false}}}
            build-result {:build-id "build-sbom-off"
                          :job-id "job-1"
                          :workspace "/tmp/test-ws"}]
        (is (nil? (sbom/generate-sbom! system build-result)))))))

;; ---------------------------------------------------------------------------
;; sbom-store CRUD tests
;; ---------------------------------------------------------------------------

(deftest sbom-store-create-get-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create and get SBOM"
      (let [sbom (sbom-store/create-sbom! ds
                   {:build-id "build-sc-1"
                    :job-id "job-1"
                    :org-id "org-1"
                    :sbom-format "cyclonedx"
                    :sbom-version "1.5"
                    :component-count 10
                    :content-hash "abcdef123456"
                    :sbom-content "{}"
                    :tool-name "syft"
                    :tool-version "0.90.0"})
            fetched (sbom-store/get-sbom ds (:id sbom))]
        (is (some? fetched))
        (is (= (:id sbom) (:id fetched)))
        (is (= "cyclonedx" (:sbom-format fetched)))
        (is (= 10 (:component-count fetched)))))))

(deftest sbom-store-get-build-sboms-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "get-build-sboms returns all SBOMs for a build"
      (sbom-store/create-sbom! ds
        {:build-id "build-sc-2" :job-id "j1" :org-id "org-1"
         :sbom-format "cyclonedx" :sbom-content "{}" :component-count 5})
      (sbom-store/create-sbom! ds
        {:build-id "build-sc-2" :job-id "j1" :org-id "org-1"
         :sbom-format "spdx" :sbom-content "{}" :component-count 3})
      (let [sboms (sbom-store/get-build-sboms ds "build-sc-2")]
        (is (= 2 (count sboms)))))))

(deftest sbom-store-list-by-org-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list SBOMs filtered by org"
      (sbom-store/create-sbom! ds
        {:build-id "build-sc-3" :job-id "j1" :org-id "org-a"
         :sbom-format "cyclonedx" :sbom-content "{}"})
      (sbom-store/create-sbom! ds
        {:build-id "build-sc-4" :job-id "j1" :org-id "org-b"
         :sbom-format "cyclonedx" :sbom-content "{}"})
      (let [org-a (sbom-store/list-sboms ds :org-id "org-a")]
        (is (= 1 (count org-a)))
        (is (= "org-a" (:org-id (first org-a))))))))

(deftest sbom-store-delete-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "delete SBOM with org check"
      (let [sbom (sbom-store/create-sbom! ds
                   {:build-id "build-sc-5" :job-id "j1" :org-id "org-a"
                    :sbom-format "cyclonedx" :sbom-content "{}"})]
        ;; Delete with wrong org should not delete
        (sbom-store/delete-sbom! ds (:id sbom) :org-id "org-b")
        (is (some? (sbom-store/get-sbom ds (:id sbom))))
        ;; Delete with correct org should work
        (sbom-store/delete-sbom! ds (:id sbom) :org-id "org-a")
        (is (nil? (sbom-store/get-sbom ds (:id sbom))))))))
