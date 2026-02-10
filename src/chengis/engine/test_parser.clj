(ns chengis.engine.test-parser
  "Parse test output from build steps into structured test results.
   Supports JUnit XML, TAP format, and generic 'X passed, Y failed' patterns."
  (:require [clojure.string :as str]
            [clojure.xml :as xml]
            [taoensso.timbre :as log])
  (:import [java.io ByteArrayInputStream]))

;; ---------------------------------------------------------------------------
;; JUnit XML parser
;; ---------------------------------------------------------------------------

(defn parse-junit-xml
  "Parse JUnit XML format test results.
   Returns a seq of {:test-name :test-suite :status :duration-ms :error-msg}."
  [xml-str]
  (when (and (string? xml-str) (str/includes? xml-str "<testsuite"))
    (try
      (let [parsed (xml/parse (ByteArrayInputStream. (.getBytes xml-str "UTF-8")))
            testcases (filter #(= :testcase (:tag %))
                              (tree-seq #(seq (:content %)) :content parsed))]
        (mapv (fn [tc]
                (let [attrs (:attrs tc)
                      failures (filter #(= :failure (:tag %)) (:content tc))
                      errors (filter #(= :error (:tag %)) (:content tc))
                      skipped (filter #(= :skipped (:tag %)) (:content tc))
                      status (cond
                               (seq errors) "error"
                               (seq failures) "fail"
                               (seq skipped) "skip"
                               :else "pass")
                      duration (when-let [t (:time attrs)]
                                 (try (long (* 1000 (Double/parseDouble t)))
                                      (catch Exception _ nil)))
                      error-msg (when (or (seq failures) (seq errors))
                                  (str/join "\n"
                                    (map #(or (first (:content %))
                                              (get-in % [:attrs :message] ""))
                                         (concat failures errors))))]
                  {:test-name (or (:name attrs) "unknown")
                   :test-suite (or (:classname attrs) (:name (:attrs parsed)))
                   :status status
                   :duration-ms duration
                   :error-msg (when (seq error-msg) error-msg)}))
              testcases))
      (catch Exception e
        (log/debug "JUnit XML parse failed:" (.getMessage e))
        nil))))

;; ---------------------------------------------------------------------------
;; TAP (Test Anything Protocol) parser
;; ---------------------------------------------------------------------------

(defn parse-tap-output
  "Parse TAP (Test Anything Protocol) format output.
   Returns a seq of {:test-name :status}."
  [output]
  (when (and (string? output) (re-find #"^(TAP version|1\.\.\d+)" output))
    (let [lines (str/split-lines output)]
      (->> lines
           (filter #(re-matches #"^(ok|not ok)\s+.*" %))
           (mapv (fn [line]
                   (let [pass? (str/starts-with? line "ok ")
                         skip? (re-find #"# skip" (str/lower-case line))
                         todo? (re-find #"# todo" (str/lower-case line))
                         name-part (-> line
                                       (str/replace #"^(ok|not ok)\s+\d*\s*-?\s*" "")
                                       (str/replace #"\s*#.*$" "")
                                       str/trim)]
                     {:test-name (if (str/blank? name-part) "unnamed" name-part)
                      :test-suite nil
                      :status (cond
                                skip? "skip"
                                todo? "skip"
                                pass? "pass"
                                :else "fail")
                      :duration-ms nil
                      :error-msg (when-not pass? line)})))
           seq))))

;; ---------------------------------------------------------------------------
;; Generic pattern parser
;; ---------------------------------------------------------------------------

(defn parse-generic-output
  "Parse generic test output looking for common patterns:
   - 'X passed, Y failed'
   - 'X tests, Y failures'
   - 'Ran X tests'
   Returns a summary map or nil."
  [output]
  (when (string? output)
    (let [;; Pattern: "X passed, Y failed"
          m1 (re-find #"(\d+)\s+(?:tests?\s+)?pass(?:ed|ing)?,?\s*(\d+)\s+(?:tests?\s+)?fail(?:ed|ing|ures?)?" output)
          ;; Pattern: "X tests, Y failures, Z errors"
          m2 (re-find #"(\d+)\s+tests?,?\s*(\d+)\s+failures?" output)
          ;; Pattern: "Ran X tests in Y seconds"
          m3 (re-find #"Ran\s+(\d+)\s+tests?" output)]
      (cond
        m1 {:total-pass (Long/parseLong (nth m1 1))
            :total-fail (Long/parseLong (nth m1 2))}
        m2 {:total-pass (- (Long/parseLong (nth m2 1)) (Long/parseLong (nth m2 2)))
            :total-fail (Long/parseLong (nth m2 2))}
        m3 {:total-run (Long/parseLong (nth m3 1))}
        :else nil))))

;; ---------------------------------------------------------------------------
;; Combined parser
;; ---------------------------------------------------------------------------

(defn extract-test-results
  "Try all parsers on the given output, return a seq of test result maps.
   Falls back to generic pattern detection."
  [output & {:keys [stage-name step-name]}]
  (when (and (string? output) (not (str/blank? output)))
    (or
      ;; Try JUnit XML first
      (parse-junit-xml output)
      ;; Try TAP format
      (parse-tap-output output)
      ;; Fall back to generic detection (returns summary, not individual tests)
      (when-let [summary (parse-generic-output output)]
        ;; Convert summary to synthetic test results
        (let [pass-count (or (:total-pass summary) (:total-run summary) 0)
              fail-count (or (:total-fail summary) 0)]
          (concat
            (when (pos? pass-count)
              [{:test-name (str (or step-name "tests") ":passed")
                :test-suite (or stage-name "default")
                :status "pass"
                :duration-ms nil
                :error-msg nil}])
            (when (pos? fail-count)
              [{:test-name (str (or step-name "tests") ":failed")
                :test-suite (or stage-name "default")
                :status "fail"
                :duration-ms nil
                :error-msg nil}])))))))
