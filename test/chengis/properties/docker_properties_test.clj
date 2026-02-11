(ns chengis.properties.docker-properties-test
  "Property-based tests for Docker command building utilities.
   Verifies shell quoting, image/name/volume/path validation,
   volume resolution, and environment flag formatting."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.engine.docker :as docker]))

;; Access private functions via var references
(def shell-quote #'chengis.engine.docker/shell-quote)
(def validate-image-name! #'chengis.engine.docker/validate-image-name!)
(def validate-name! #'chengis.engine.docker/validate-name!)
(def validate-cache-volume-name! #'chengis.engine.docker/validate-cache-volume-name!)
(def validate-mount-path! #'chengis.engine.docker/validate-mount-path!)
(def env-flags #'chengis.engine.docker/env-flags)
(def volume-flags #'chengis.engine.docker/volume-flags)

;; ---------------------------------------------------------------------------
;; shell-quote — quoting invariants
;; ---------------------------------------------------------------------------

(defspec shell-quote-wraps-in-single-quotes 200
  (prop/for-all [s cgen/gen-shell-quotable-string]
    (let [quoted (shell-quote s)]
      (and (str/starts-with? quoted "'")
           (str/ends-with? quoted "'")))))

(defspec shell-quote-no-bare-single-quotes 200
  (prop/for-all [s cgen/gen-shell-quotable-string]
    (let [quoted (shell-quote s)
          interior (subs quoted 1 (dec (count quoted)))
          ;; Remove all escape sequences '\'' from interior
          cleaned (str/replace interior "'\\''" "")]
      (not (str/includes? cleaned "'")))))

;; ---------------------------------------------------------------------------
;; validate-image-name! — acceptance and rejection
;; ---------------------------------------------------------------------------

(defspec validate-image-accepts-valid 200
  (prop/for-all [image cgen/gen-docker-image-name]
    (try
      (validate-image-name! image)
      true
      (catch Exception _
        false))))

(defspec validate-image-rejects-blank 100
  (prop/for-all [s (gen/elements ["" " " "  "])]
    (try
      (validate-image-name! s)
      false
      (catch Exception _
        true))))

(defspec validate-image-rejects-special-chars 100
  (prop/for-all [image cgen/gen-invalid-docker-image]
    (try
      (validate-image-name! image)
      false
      (catch Exception _
        true))))

;; ---------------------------------------------------------------------------
;; validate-name! — acceptance and rejection
;; ---------------------------------------------------------------------------

(defspec validate-name-accepts-valid 200
  (prop/for-all [name cgen/gen-docker-name]
    (try
      (validate-name! name "test")
      true
      (catch Exception _
        false))))

(defspec validate-name-rejects-invalid 200
  (prop/for-all [name cgen/gen-invalid-docker-name]
    (try
      (validate-name! name "test")
      false
      (catch Exception _
        true))))

;; ---------------------------------------------------------------------------
;; validate-cache-volume-name! — acceptance
;; ---------------------------------------------------------------------------

(defspec validate-cache-volume-name-accepts-valid 200
  (prop/for-all [vol-name cgen/gen-cache-volume-name]
    (try
      (validate-cache-volume-name! vol-name)
      true
      (catch Exception _
        false))))

;; ---------------------------------------------------------------------------
;; validate-mount-path! — acceptance and rejection
;; ---------------------------------------------------------------------------

(defspec validate-mount-path-accepts-valid 200
  (prop/for-all [path (gen/such-that #(<= (count %) 256) cgen/gen-valid-mount-path)]
    (try
      (validate-mount-path! path)
      true
      (catch Exception _
        false))))

(defspec validate-mount-path-rejects-relative 100
  (prop/for-all [_ (gen/return nil)]
    (try
      (validate-mount-path! "relative/path")
      false
      (catch Exception _
        true))))

(defspec validate-mount-path-rejects-traversal 100
  (prop/for-all [_ (gen/return nil)]
    (try
      (validate-mount-path! "/bad/../traversal")
      false
      (catch Exception _
        true))))

;; ---------------------------------------------------------------------------
;; resolve-volumes — token substitution
;; ---------------------------------------------------------------------------

(defspec resolve-volumes-nil-returns-nil 10
  (prop/for-all [_ (gen/return nil)]
    (and (nil? (docker/resolve-volumes "/workspace" nil))
         (nil? (docker/resolve-volumes "/workspace" [])))))

(defspec resolve-volumes-replaces-workspace-token 200
  (prop/for-all [container-path cgen/gen-volume-spec-with-token]
    (let [ws "/my/workspace"
          result (docker/resolve-volumes ws [container-path])]
      (and (some? result)
           (not (str/includes? (first result) "${WORKSPACE}"))
           (str/includes? (first result) ws)))))

(defspec resolve-volumes-no-token-passthrough 200
  (prop/for-all [vol-spec cgen/gen-volume-spec]
    (let [result (docker/resolve-volumes "/ws" [vol-spec])]
      (= (first result) vol-spec))))

;; ---------------------------------------------------------------------------
;; env-flags — count and structure invariants
;; ---------------------------------------------------------------------------

(defspec env-flags-count-invariant 200
  (prop/for-all [env-map (gen/not-empty cgen/gen-env-map)]
    (let [flags (vec (env-flags env-map))]
      (= (count flags) (* 2 (count env-map))))))

(defspec env-flags-all-start-with-dash-e 200
  (prop/for-all [env-map (gen/not-empty cgen/gen-env-map)]
    (let [flags (vec (env-flags env-map))]
      (every? #(= "-e" (nth flags %))
              (range 0 (count flags) 2)))))
