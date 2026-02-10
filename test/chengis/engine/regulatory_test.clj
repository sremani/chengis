(ns chengis.engine.regulatory-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.audit-store :as audit-store]
            [chengis.db.regulatory-store :as regulatory-store]
            [chengis.engine.regulatory :as regulatory]))

(def test-db-path "/tmp/chengis-regulatory-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-system
  [& {:keys [auth-enabled metrics-enabled slsa-provenance artifact-checksums
             policy-engine sbom-generation regulatory-dashboards]
      :or {auth-enabled false metrics-enabled false slsa-provenance false
           artifact-checksums false policy-engine false sbom-generation false
           regulatory-dashboards true}}]
  (let [ds (conn/create-datasource test-db-path)]
    {:db ds
     :config {:auth {:enabled auth-enabled}
              :metrics {:enabled metrics-enabled}
              :feature-flags {:slsa-provenance slsa-provenance
                              :artifact-checksums artifact-checksums
                              :policy-engine policy-engine
                              :sbom-generation sbom-generation
                              :regulatory-dashboards regulatory-dashboards}}}))

;; ---------------------------------------------------------------------------
;; SOC 2 assessment tests
;; ---------------------------------------------------------------------------

(deftest assess-soc2-auth-enabled-test
  (testing "SOC 2 CC6.1 passes when auth is enabled"
    (let [system (test-system :auth-enabled true)
          checks (regulatory/assess-soc2-readiness system "org-1")]
      (is (= 5 (count checks)))
      (let [cc61 (first (filter #(= "CC6.1" (:control-id %)) checks))]
        (is (= "passing" (:status cc61)))
        (is (= "Access Control" (:control-name cc61)))))))

(deftest assess-soc2-auth-disabled-test
  (testing "SOC 2 CC6.1 fails when auth is disabled"
    (let [system (test-system :auth-enabled false)
          checks (regulatory/assess-soc2-readiness system "org-1")]
      (let [cc61 (first (filter #(= "CC6.1" (:control-id %)) checks))]
        (is (= "failing" (:status cc61)))))))

;; ---------------------------------------------------------------------------
;; ISO 27001 assessment tests
;; ---------------------------------------------------------------------------

(deftest assess-iso27001-all-enabled-test
  (testing "ISO 27001 all controls pass when all flags enabled"
    (let [system (test-system :policy-engine true :sbom-generation true)]
      ;; Insert an audit entry so A.12.4 passes
      (audit-store/insert-audit! (:db system)
        {:user-id "u1" :username "alice" :action "login"
         :resource-type "session" :resource-id "s1" :org-id "org-1"})
      (let [checks (regulatory/assess-iso27001-readiness system "org-1")]
        (is (= 3 (count checks)))
        (is (every? #(= "passing" (:status %)) checks))))))

(deftest assess-iso27001-all-disabled-test
  (testing "ISO 27001 all controls fail when all flags disabled"
    (let [system (test-system :policy-engine false :sbom-generation false)
          checks (regulatory/assess-iso27001-readiness system "org-1")]
      (is (= 3 (count checks)))
      (is (every? #(= "failing" (:status %)) checks)))))

;; ---------------------------------------------------------------------------
;; compute-readiness-score tests
;; ---------------------------------------------------------------------------

(deftest compute-readiness-score-all-passing-test
  (testing "compute-readiness-score with all passing"
    (let [checks [{:control-id "C1" :status "passing"}
                  {:control-id "C2" :status "passing"}
                  {:control-id "C3" :status "passing"}]
          score (regulatory/compute-readiness-score checks)]
      (is (= 3 (:total score)))
      (is (= 3 (:passing score)))
      (is (= 0 (:failing score)))
      (is (= 0 (:not-assessed score)))
      (is (< (Math/abs (- 100.0 (:percentage score))) 0.01)))))

(deftest compute-readiness-score-mixed-test
  (testing "compute-readiness-score with mixed results"
    (let [checks [{:control-id "C1" :status "passing"}
                  {:control-id "C2" :status "failing"}
                  {:control-id "C3" :status "not-assessed"}
                  {:control-id "C4" :status "passing"}]
          score (regulatory/compute-readiness-score checks)]
      (is (= 4 (:total score)))
      (is (= 2 (:passing score)))
      (is (= 1 (:failing score)))
      (is (= 1 (:not-assessed score)))
      (is (< (Math/abs (- 50.0 (:percentage score))) 0.01)))))

(deftest compute-readiness-score-empty-test
  (testing "compute-readiness-score with empty checks"
    (let [score (regulatory/compute-readiness-score [])]
      (is (= 0 (:total score)))
      (is (= 0 (:passing score)))
      (is (= 0.0 (:percentage score))))))

;; ---------------------------------------------------------------------------
;; assess-and-store! tests
;; ---------------------------------------------------------------------------

(deftest assess-and-store-persistence-test
  (testing "assess-and-store! persists checks to DB"
    (let [system (test-system :auth-enabled true :metrics-enabled true
                              :slsa-provenance true :artifact-checksums true
                              :policy-engine true :sbom-generation true)]
      ;; Insert audit entry so audit-dependent checks pass
      (audit-store/insert-audit! (:db system)
        {:user-id "u1" :username "admin" :action "login"
         :resource-type "session" :resource-id "s1" :org-id "org-1"})
      (let [result (regulatory/assess-and-store! system "org-1")]
        (is (some? result))
        ;; SOC 2: 5 controls, all passing
        (is (= 5 (:total (:soc2 result))))
        (is (= 5 (:passing (:soc2 result))))
        (is (< (Math/abs (- 100.0 (:percentage (:soc2 result)))) 0.01))
        ;; ISO 27001: 3 controls, all passing
        (is (= 3 (:total (:iso27001 result))))
        (is (= 3 (:passing (:iso27001 result))))
        ;; Verify DB persistence
        (let [soc2-checks (regulatory-store/get-framework-checks (:db system) "soc2"
                            :org-id "org-1")]
          (is (= 5 (count soc2-checks)))
          (is (every? #(= "passing" (:status %)) soc2-checks)))
        (let [iso-checks (regulatory-store/get-framework-checks (:db system) "iso27001"
                           :org-id "org-1")]
          (is (= 3 (count iso-checks))))))))

(deftest assess-and-store-feature-flag-disabled-test
  (testing "assess-and-store! returns nil when feature flag disabled"
    (let [system (test-system :regulatory-dashboards false)]
      (is (nil? (regulatory/assess-and-store! system "org-1"))))))

;; ---------------------------------------------------------------------------
;; regulatory-store tests
;; ---------------------------------------------------------------------------

(deftest regulatory-store-upsert-and-query-test
  (testing "regulatory store: upsert, get-framework-checks, readiness-summary"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Insert checks for SOC 2
      (regulatory-store/upsert-check! ds
        {:org-id "org-1" :framework "soc2" :control-id "CC6.1"
         :control-name "Access Control" :status "passing"
         :evidence-summary "Auth enabled"})
      (regulatory-store/upsert-check! ds
        {:org-id "org-1" :framework "soc2" :control-id "CC6.6"
         :control-name "Audit Logging" :status "failing"
         :evidence-summary "No audit entries"})
      ;; Insert check for ISO
      (regulatory-store/upsert-check! ds
        {:org-id "org-1" :framework "iso27001" :control-id "A.12.1"
         :control-name "Operational Procedures" :status "passing"
         :evidence-summary "Policy engine active"})

      ;; Get framework checks
      (let [soc2 (regulatory-store/get-framework-checks ds "soc2" :org-id "org-1")]
        (is (= 2 (count soc2)))
        (is (some #(= "CC6.1" (:control-id %)) soc2)))

      ;; List frameworks
      (let [frameworks (regulatory-store/list-frameworks ds :org-id "org-1")]
        (is (= 2 (count frameworks)))
        (is (some #(= "soc2" %) frameworks))
        (is (some #(= "iso27001" %) frameworks)))

      ;; Readiness summary
      (let [summary (regulatory-store/get-readiness-summary ds :org-id "org-1")]
        (is (= 2 (count summary)))
        (let [soc2-summary (first (filter #(= "soc2" (:framework %)) summary))]
          (is (= 2 (:total soc2-summary)))
          (is (= 1 (:passing soc2-summary)))
          (is (= 1 (:failing soc2-summary)))
          (is (= 50.0 (:percentage soc2-summary)))))

      ;; Upsert (update existing)
      (regulatory-store/upsert-check! ds
        {:org-id "org-1" :framework "soc2" :control-id "CC6.6"
         :control-name "Audit Logging" :status "passing"
         :evidence-summary "Audit entries now present"})
      (let [soc2 (regulatory-store/get-framework-checks ds "soc2" :org-id "org-1")
            cc66 (first (filter #(= "CC6.6" (:control-id %)) soc2))]
        (is (= "passing" (:status cc66)))
        (is (= "Audit entries now present" (:evidence-summary cc66)))))))
