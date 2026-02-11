(ns chengis.properties.build-compare-properties-test
  "Property-based tests for build comparison engine.
   Covers parse-duration (private), match-by-name (private),
   compare-artifacts (private), and compare-builds (public)."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.set :as set]
            [chengis.generators :as cgen]
            [chengis.engine.build-compare :as build-compare]))

(def ^:private parse-duration #'chengis.engine.build-compare/parse-duration)
(def ^:private match-by-name #'chengis.engine.build-compare/match-by-name)
(def ^:private compare-artifacts #'chengis.engine.build-compare/compare-artifacts)

;; ---------------------------------------------------------------------------
;; parse-duration — nil inputs yield nil
;; ---------------------------------------------------------------------------

(defspec parse-duration-nil-start-returns-nil 50
  (prop/for-all [ts cgen/gen-iso-timestamp]
    (nil? (parse-duration nil ts))))

(defspec parse-duration-nil-end-returns-nil 50
  (prop/for-all [ts cgen/gen-iso-timestamp]
    (nil? (parse-duration ts nil))))

(defspec parse-duration-both-nil-returns-nil 20
  (prop/for-all [_ (gen/return nil)]
    (nil? (parse-duration nil nil))))

;; ---------------------------------------------------------------------------
;; parse-duration — valid pair returns non-negative seconds
;; ---------------------------------------------------------------------------

(defspec parse-duration-valid-pair-non-negative 200
  (prop/for-all [tp cgen/gen-timestamp-pair]
    (let [result (parse-duration (:started-at tp) (:completed-at tp))]
      (if (nil? result)
        true
        (>= result 0)))))

;; ---------------------------------------------------------------------------
;; parse-duration — same timestamp yields zero
;; ---------------------------------------------------------------------------

(defspec parse-duration-same-timestamp-is-zero 100
  (prop/for-all [ts cgen/gen-iso-timestamp]
    (let [result (parse-duration ts ts)]
      (if (nil? result)
        true
        (= 0 result)))))

;; ---------------------------------------------------------------------------
;; match-by-name — full outer join covers all names
;; ---------------------------------------------------------------------------

(defspec match-by-name-covers-all-names 200
  (prop/for-all [stages-a cgen/gen-stage-records
                 stages-b cgen/gen-stage-records]
    (let [matched (match-by-name stages-a stages-b :stage-name)
          all-names (set (concat (map :stage-name stages-a) (map :stage-name stages-b)))
          matched-names (set (map :name matched))]
      (= all-names matched-names))))

;; ---------------------------------------------------------------------------
;; match-by-name — items in both collections have :a and :b
;; ---------------------------------------------------------------------------

(defspec match-by-name-shared-items-have-both 100
  (prop/for-all [stages-a cgen/gen-stage-records
                 stages-b cgen/gen-stage-records]
    (let [names-a (set (map :stage-name stages-a))
          names-b (set (map :stage-name stages-b))
          shared (set/intersection names-a names-b)
          matched (match-by-name stages-a stages-b :stage-name)]
      (every? (fn [m]
                (if (contains? shared (:name m))
                  (and (some? (:a m)) (some? (:b m)))
                  true))
              matched))))

;; ---------------------------------------------------------------------------
;; compare-artifacts — only-in-a + in-both + only-in-b covers all filenames
;; ---------------------------------------------------------------------------

(defspec compare-artifacts-covers-all-filenames 200
  (prop/for-all [arts-a cgen/gen-artifact-records
                 arts-b cgen/gen-artifact-records]
    (let [result (compare-artifacts arts-a arts-b)
          all-filenames (set (concat (map :filename arts-a) (map :filename arts-b)))
          result-filenames (set (concat (:only-in-a result)
                                        (:only-in-b result)
                                        (:in-both result)))]
      (= all-filenames result-filenames))))

;; ---------------------------------------------------------------------------
;; compare-artifacts — only-in-a and only-in-b are disjoint
;; ---------------------------------------------------------------------------

(defspec compare-artifacts-only-sets-disjoint 100
  (prop/for-all [arts-a cgen/gen-artifact-records
                 arts-b cgen/gen-artifact-records]
    (let [result (compare-artifacts arts-a arts-b)]
      (empty? (set/intersection (set (:only-in-a result))
                                (set (:only-in-b result)))))))

;; ---------------------------------------------------------------------------
;; compare-artifacts — size-changes only for files in-both with different sizes
;; ---------------------------------------------------------------------------

(defspec compare-artifacts-size-changes-subset-of-both 100
  (prop/for-all [arts-a cgen/gen-artifact-records
                 arts-b cgen/gen-artifact-records]
    (let [result (compare-artifacts arts-a arts-b)
          both-set (set (:in-both result))
          changed-files (set (map :filename (:size-changes result)))]
      (set/subset? changed-files both-set))))

;; ---------------------------------------------------------------------------
;; compare-builds — summary status-changed? correctness
;; ---------------------------------------------------------------------------

(defspec compare-builds-status-changed-correct 200
  (prop/for-all [build-a cgen/gen-build-record
                 build-b cgen/gen-build-record]
    (let [result (build-compare/compare-builds
                   build-a [] [] []
                   build-b [] [] [])]
      (= (get-in result [:summary :status-changed?])
         (not= (:status build-a) (:status build-b))))))

;; ---------------------------------------------------------------------------
;; compare-builds — stages-added/removed reflect set difference
;; ---------------------------------------------------------------------------

(defspec compare-builds-stages-added-removed 200
  (prop/for-all [stages-a cgen/gen-stage-records
                 stages-b cgen/gen-stage-records
                 build-a cgen/gen-build-record
                 build-b cgen/gen-build-record]
    (let [result (build-compare/compare-builds
                   build-a stages-a [] []
                   build-b stages-b [] [])
          names-a (set (map :stage-name stages-a))
          names-b (set (map :stage-name stages-b))
          expected-added (sort (set/difference names-b names-a))
          expected-removed (sort (set/difference names-a names-b))]
      (and (= (vec expected-added) (get-in result [:summary :stages-added]))
           (= (vec expected-removed) (get-in result [:summary :stages-removed]))))))

;; ---------------------------------------------------------------------------
;; compare-builds — result always contains expected top-level keys
;; ---------------------------------------------------------------------------

(defspec compare-builds-has-expected-keys 100
  (prop/for-all [build-a cgen/gen-build-record
                 build-b cgen/gen-build-record]
    (let [result (build-compare/compare-builds
                   build-a [] [] []
                   build-b [] [] [])]
      (and (contains? result :build-a)
           (contains? result :build-b)
           (contains? result :summary)
           (contains? result :stages)
           (contains? result :artifacts)))))

;; ---------------------------------------------------------------------------
;; compare-builds — stage diffs count matches union of stage names
;; ---------------------------------------------------------------------------

(defspec compare-builds-stage-diff-count 100
  (prop/for-all [stages-a cgen/gen-stage-records
                 stages-b cgen/gen-stage-records
                 build-a cgen/gen-build-record
                 build-b cgen/gen-build-record]
    (let [result (build-compare/compare-builds
                   build-a stages-a [] []
                   build-b stages-b [] [])
          all-names (set (concat (map :stage-name stages-a) (map :stage-name stages-b)))]
      (= (count all-names) (count (:stages result))))))
