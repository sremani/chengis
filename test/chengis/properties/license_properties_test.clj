(ns chengis.properties.license-properties-test
  "Property-based tests for license scanning and policy evaluation.
   Verifies SBOM extraction resilience, policy partition invariant
   (allowed + denied + unknown = total), and pass/fail correctness."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.engine.license-scanner :as license]))

;; ---------------------------------------------------------------------------
;; extract-licenses-from-sbom — resilience
;; ---------------------------------------------------------------------------

(defspec sbom-extraction-never-throws-on-arbitrary-input 200
  (prop/for-all [s gen/string]
    (let [result (license/extract-licenses-from-sbom s)]
      (vector? result))))

(defspec sbom-extraction-valid-json-returns-components 200
  (prop/for-all [sbom-json cgen/gen-cyclonedx-sbom-json]
    (let [result (license/extract-licenses-from-sbom sbom-json)]
      (and (vector? result)
           (every? (fn [c]
                     (and (contains? c :component-name)
                          (contains? c :component-version)
                          (contains? c :license-id)))
                   result)))))

(defspec sbom-extraction-handles-missing-licenses 50
  (prop/for-all [_ (gen/return nil)]
    ;; Components with no license info should get "unknown"
    (let [sbom-json "{\"components\":[{\"name\":\"foo\",\"version\":\"1.0\"}]}"
          result (license/extract-licenses-from-sbom sbom-json)]
      (and (= 1 (count result))
           (= "unknown" (:license-id (first result)))))))

;; ---------------------------------------------------------------------------
;; evaluate-license-policy — partition invariant
;; ---------------------------------------------------------------------------

(defspec license-policy-partition 200
  (prop/for-all [components (gen/vector cgen/gen-license-component 0 10)
                 policy cgen/gen-license-policy]
    (let [result (license/evaluate-license-policy components policy)]
      ;; allowed + denied + unknown = total components
      (= (count components)
         (+ (:allowed result)
            (:denied result)
            (:unknown result))))))

(defspec license-policy-passed-iff-no-denied 200
  (prop/for-all [components (gen/vector cgen/gen-license-component 0 10)
                 policy cgen/gen-license-policy]
    (let [result (license/evaluate-license-policy components policy)]
      (= (:passed? result)
         (zero? (:denied result))))))

(defspec license-policy-empty-policy-all-unknown 100
  (prop/for-all [components (gen/vector cgen/gen-license-component 1 5)]
    (let [result (license/evaluate-license-policy components [])]
      (and (= (count components) (:unknown result))
           (= 0 (:allowed result))
           (= 0 (:denied result))
           (true? (:passed? result))))))

(defspec license-policy-all-allow-no-denied 100
  (prop/for-all [components (gen/vector cgen/gen-license-component 1 5)]
    ;; If every license is in the allow list, none should be denied
    (let [all-licenses (set (map :license-id components))
          policy (mapv (fn [l] {:license-id l :action "allow"}) all-licenses)
          result (license/evaluate-license-policy components policy)]
      (and (zero? (:denied result))
           (true? (:passed? result))))))

(defspec license-policy-all-deny-all-denied 100
  (prop/for-all [components (gen/vector cgen/gen-license-component 1 5)]
    ;; If every license is in the deny list, all should be denied
    (let [all-licenses (set (map :license-id components))
          policy (mapv (fn [l] {:license-id l :action "deny"}) all-licenses)
          result (license/evaluate-license-policy components policy)]
      (and (= (count components) (:denied result))
           (false? (:passed? result))))))

(defspec license-policy-deny-takes-precedence 100
  (prop/for-all [license cgen/gen-license-id]
    ;; When same license is both allowed and denied, deny wins
    ;; (because deny-set is checked first in cond)
    (let [components [{:component-name "test" :component-version "1.0" :license-id license}]
          policy [{:license-id license :action "allow"}
                  {:license-id license :action "deny"}]
          result (license/evaluate-license-policy components policy)]
      (= "denied" (:status (first (:components result)))))))
