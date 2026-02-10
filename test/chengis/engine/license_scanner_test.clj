(ns chengis.engine.license-scanner-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.license-store :as license-store]
            [chengis.db.sbom-store :as sbom-store]
            [chengis.engine.license-scanner :as scanner]
            [chengis.feature-flags :as feature-flags]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-license-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; extract-licenses-from-sbom tests
;; ---------------------------------------------------------------------------

(deftest extract-licenses-cyclonedx-test
  (testing "extracts licenses from CycloneDX JSON with license IDs"
    (let [sbom-json (json/write-str
                      {:bomFormat "CycloneDX"
                       :components [{:name "lib-a" :version "1.0"
                                     :licenses [{:license {:id "MIT"}}]}
                                    {:name "lib-b" :version "2.0"
                                     :licenses [{:license {:id "GPL-3.0"}}]}
                                    {:name "lib-c" :version "3.0"}]})
          result (scanner/extract-licenses-from-sbom sbom-json)]
      (is (= 3 (count result)))
      (is (= "MIT" (:license-id (first result))))
      (is (= "GPL-3.0" (:license-id (second result))))
      (is (= "unknown" (:license-id (nth result 2))))
      (is (= "lib-a" (:component-name (first result))))
      (is (= "1.0" (:component-version (first result)))))))

(deftest extract-licenses-empty-components-test
  (testing "handles empty components array"
    (let [sbom-json (json/write-str {:bomFormat "CycloneDX" :components []})
          result (scanner/extract-licenses-from-sbom sbom-json)]
      (is (= 0 (count result)))
      (is (vector? result)))))

(deftest extract-licenses-malformed-json-test
  (testing "handles malformed JSON gracefully"
    (let [result (scanner/extract-licenses-from-sbom "not-valid-json{{{")]
      (is (= 0 (count result)))
      (is (vector? result)))))

;; ---------------------------------------------------------------------------
;; evaluate-license-policy tests
;; ---------------------------------------------------------------------------

(deftest evaluate-license-policy-all-allowed-test
  (testing "all components allowed when policy lists them as allow"
    (let [components [{:component-name "a" :component-version "1" :license-id "MIT"}
                      {:component-name "b" :component-version "2" :license-id "Apache-2.0"}]
          policies [{:license-id "MIT" :action "allow"}
                    {:license-id "Apache-2.0" :action "allow"}]
          result (scanner/evaluate-license-policy components policies)]
      (is (= 2 (:allowed result)))
      (is (= 0 (:denied result)))
      (is (= 0 (:unknown result)))
      (is (true? (:passed? result))))))

(deftest evaluate-license-policy-denied-test
  (testing "denied license causes passed? to be false"
    (let [components [{:component-name "a" :component-version "1" :license-id "MIT"}
                      {:component-name "b" :component-version "2" :license-id "GPL-3.0"}]
          policies [{:license-id "MIT" :action "allow"}
                    {:license-id "GPL-3.0" :action "deny"}]
          result (scanner/evaluate-license-policy components policies)]
      (is (= 1 (:allowed result)))
      (is (= 1 (:denied result)))
      (is (= 0 (:unknown result)))
      (is (false? (:passed? result)))
      (is (= "denied" (:status (second (:components result))))))))

(deftest evaluate-license-policy-unknown-test
  (testing "license not in policy is marked unknown"
    (let [components [{:component-name "a" :component-version "1" :license-id "LGPL-2.1"}]
          policies [{:license-id "MIT" :action "allow"}]
          result (scanner/evaluate-license-policy components policies)]
      (is (= 0 (:allowed result)))
      (is (= 0 (:denied result)))
      (is (= 1 (:unknown result)))
      (is (true? (:passed? result))))))

(deftest evaluate-license-policy-empty-policies-test
  (testing "empty policy list marks all as unknown"
    (let [components [{:component-name "a" :component-version "1" :license-id "MIT"}
                      {:component-name "b" :component-version "2" :license-id "BSD-3-Clause"}]
          result (scanner/evaluate-license-policy components [])]
      (is (= 0 (:allowed result)))
      (is (= 0 (:denied result)))
      (is (= 2 (:unknown result)))
      (is (true? (:passed? result))))))

;; ---------------------------------------------------------------------------
;; License store CRUD tests
;; ---------------------------------------------------------------------------

(deftest license-store-report-crud-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create and retrieve license report"
      (let [report (license-store/create-report! ds
                     {:build-id "b-1" :job-id "j-1" :org-id "org-A"
                      :total-deps 5 :allowed-count 3 :denied-count 1
                      :unknown-count 1 :policy-passed false
                      :licenses-json "[{\"name\":\"lib-a\",\"license\":\"MIT\"}]"})
            fetched (license-store/get-build-report ds "b-1")]
        (is (some? (:id report)))
        (is (= "b-1" (:build-id fetched)))
        (is (= 5 (:total-deps fetched)))
        (is (= 3 (:allowed-count fetched)))
        (is (= 1 (:denied-count fetched)))
        (is (= 0 (:policy-passed fetched)))))

    (testing "list-reports filters by org"
      (license-store/create-report! ds
        {:build-id "b-2" :job-id "j-2" :org-id "org-B"
         :total-deps 2 :allowed-count 2 :denied-count 0
         :unknown-count 0 :policy-passed true})
      (let [org-a (license-store/list-reports ds :org-id "org-A")
            org-b (license-store/list-reports ds :org-id "org-B")]
        (is (= 1 (count org-a)))
        (is (= 1 (count org-b)))))))

(deftest license-store-policy-crud-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create, list, and delete license policies"
      (let [p1 (license-store/create-license-policy! ds
                 {:org-id "org-A" :license-id "MIT" :action "allow"})
            p2 (license-store/create-license-policy! ds
                 {:org-id "org-A" :license-id "GPL-3.0" :action "deny"})]
        ;; List
        (let [policies (license-store/list-license-policies ds :org-id "org-A")]
          (is (= 2 (count policies)))
          ;; Ordered by license_id asc
          (is (= "GPL-3.0" (:license-id (first policies))))
          (is (= "MIT" (:license-id (second policies)))))
        ;; Delete
        (license-store/delete-license-policy! ds (:id p1) :org-id "org-A")
        (let [remaining (license-store/list-license-policies ds :org-id "org-A")]
          (is (= 1 (count remaining)))
          (is (= "GPL-3.0" (:license-id (first remaining)))))))))

;; ---------------------------------------------------------------------------
;; scan-licenses! integration tests
;; ---------------------------------------------------------------------------

(deftest scan-licenses-with-sbom-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "scan-licenses! with existing SBOM produces a report"
      ;; Insert an SBOM
      (let [sbom-json (json/write-str
                        {:bomFormat "CycloneDX"
                         :components [{:name "lib-a" :version "1.0"
                                       :licenses [{:license {:id "MIT"}}]}
                                      {:name "lib-b" :version "2.0"
                                       :licenses [{:license {:id "Apache-2.0"}}]}]})]
        (sbom-store/create-sbom! ds
          {:build-id "b-scan-1" :job-id "j-1" :org-id "org-A"
           :sbom-format "cyclonedx-json" :sbom-content sbom-json})
        ;; Create license policies
        (license-store/create-license-policy! ds
          {:org-id "org-A" :license-id "MIT" :action "allow"})
        (license-store/create-license-policy! ds
          {:org-id "org-A" :license-id "Apache-2.0" :action "allow"})
        ;; Run scan
        (let [system {:db ds :config {:feature-flags {:license-scanning true}}}
              result (scanner/scan-licenses! system
                       {:build-id "b-scan-1" :job-id "j-1" :org-id "org-A"})]
          (is (some? result))
          (is (= "b-scan-1" (:build-id result)))
          (is (= 2 (:total-deps result)))
          (is (= 2 (:allowed-count result)))
          (is (= 0 (:denied-count result)))
          (is (= 1 (:policy-passed result)))
          ;; Verify stored in DB
          (let [db-report (license-store/get-build-report ds "b-scan-1")]
            (is (some? db-report))
            (is (= 2 (:total-deps db-report)))))))))

(deftest scan-licenses-no-sbom-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "scan-licenses! with no SBOM returns nil"
      (let [system {:db ds :config {:feature-flags {:license-scanning true}}}
            result (scanner/scan-licenses! system
                     {:build-id "b-no-sbom" :job-id "j-1" :org-id "org-A"})]
        (is (nil? result))))))

(deftest scan-licenses-feature-flag-disabled-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "scan-licenses! returns nil when feature flag disabled"
      ;; Insert an SBOM that would normally be scanned
      (sbom-store/create-sbom! ds
        {:build-id "b-flag-off" :job-id "j-1" :org-id "org-A"
         :sbom-format "cyclonedx-json"
         :sbom-content (json/write-str {:bomFormat "CycloneDX"
                                         :components [{:name "lib" :version "1"
                                                       :licenses [{:license {:id "MIT"}}]}]})})
      (let [system {:db ds :config {:feature-flags {:license-scanning false}}}
            result (scanner/scan-licenses! system
                     {:build-id "b-flag-off" :job-id "j-1" :org-id "org-A"})]
        (is (nil? result))))))
