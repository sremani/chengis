(ns chengis.properties.shell-safety-properties-test
  "Property-based tests for shell quoting and HTML escaping.
   These are security-critical functions — property tests ensure they
   handle adversarial inputs without producing unsafe output."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.engine.iac :as iac]
            [hiccup.util :refer [escape-html]]))

;; Access private shell-quote via var reference
(def shell-quote #'chengis.engine.iac/shell-quote)

;; ---------------------------------------------------------------------------
;; Shell quoting properties
;; ---------------------------------------------------------------------------

(defspec shell-quote-wraps-in-single-quotes 200
  (prop/for-all [s gen/string]
    (let [quoted (shell-quote s)]
      (and (str/starts-with? quoted "'")
           (str/ends-with? quoted "'")))))

(defspec shell-quote-produces-string 200
  (prop/for-all [s gen/string]
    (string? (shell-quote s))))

(defspec shell-quote-adversarial-inputs 500
  (prop/for-all [s cgen/gen-adversarial-string]
    (let [quoted (shell-quote s)]
      ;; Quoted string must start and end with single quotes
      ;; and any embedded single quotes must be properly escaped
      (and (string? quoted)
           (str/starts-with? quoted "'")
           (str/ends-with? quoted "'")))))

(defspec shell-quote-no-unescaped-embedded-quotes 300
  (prop/for-all [s gen/string]
    (let [quoted (shell-quote s)
          ;; Remove outer quotes to get inner content
          inner (subs quoted 1 (dec (count quoted)))
          ;; The escape pattern for single quotes is: '\''
          ;; Remove all escape sequences, then check no bare quotes remain
          without-escapes (str/replace inner "'\\''" "")]
      ;; After stripping all escape sequences, no single quotes should remain
      (not (str/includes? without-escapes "'")))))

(defspec shell-quote-alphanumeric-preserves-content 200
  (prop/for-all [s gen/string-alphanumeric]
    ;; For simple alphanumeric strings, content should be preserved
    (let [quoted (shell-quote s)]
      (= (str "'" s "'") quoted))))

;; ---------------------------------------------------------------------------
;; HTML escaping properties
;; ---------------------------------------------------------------------------

(defspec escape-html-produces-string 200
  (prop/for-all [s gen/string]
    (string? (escape-html s))))

(defspec escape-html-no-raw-angle-brackets 200
  (prop/for-all [s gen/string]
    (let [escaped (escape-html s)]
      ;; After escaping, no raw < or > should remain.
      ;; Entity references (&lt; &gt;) don't contain bare < or >.
      (and (not (str/includes? escaped "<"))
           (not (str/includes? escaped ">"))))))

(defspec escape-html-adversarial-inputs 300
  (prop/for-all [s cgen/gen-adversarial-string]
    (let [escaped (escape-html s)]
      (and (string? escaped)
           ;; No raw script tags should survive
           (not (str/includes? escaped "<script>"))
           (not (str/includes? escaped "</script>"))))))

(defspec escape-html-ampersand-escaping 100
  (prop/for-all [s gen/string]
    (let [escaped (escape-html s)]
      ;; All literal & in input should become &amp; in output
      ;; (if the original had & and the escaped doesn't contain more &amp;
      ;;  then something went wrong — but we just check no raw & remains
      ;;  that isn't part of an entity)
      (or (not (str/includes? s "&"))
          (str/includes? escaped "&amp;")))))

;; ---------------------------------------------------------------------------
;; Command builder properties
;; ---------------------------------------------------------------------------

(defspec terraform-command-contains-binary 100
  (prop/for-all [opts cgen/gen-terraform-opts]
    (let [cmd (iac/build-terraform-command opts)]
      (and (string? cmd)
           (pos? (count cmd))
           (str/includes? cmd "terraform")))))

(defspec terraform-command-contains-action 100
  (prop/for-all [opts cgen/gen-terraform-opts]
    (let [cmd (iac/build-terraform-command opts)]
      ;; The action should appear in the command string
      (str/includes? cmd (:action opts)))))

(defspec pulumi-command-contains-binary 100
  (prop/for-all [opts cgen/gen-pulumi-opts]
    (let [cmd (iac/build-pulumi-command opts)]
      (and (string? cmd)
           (pos? (count cmd))
           (str/includes? cmd "pulumi")))))

(defspec pulumi-command-non-interactive 100
  (prop/for-all [opts cgen/gen-pulumi-opts]
    (let [cmd (iac/build-pulumi-command opts)]
      (str/includes? cmd "--non-interactive"))))

(defspec cloudformation-command-contains-aws 100
  (prop/for-all [opts cgen/gen-cloudformation-opts]
    (let [cmd (iac/build-cloudformation-command opts)]
      (and (string? cmd)
           (pos? (count cmd))
           (str/includes? cmd "aws")
           (str/includes? cmd "cloudformation")))))

(defspec cloudformation-command-json-output 100
  (prop/for-all [opts cgen/gen-cloudformation-opts]
    (let [cmd (iac/build-cloudformation-command opts)]
      ;; All CloudFormation commands should request JSON output
      (str/includes? cmd "--output json"))))
