(ns cljest.reporter-test
  "Tests for the reporter module — mutation score calculation, text report
   generation, and HTML report generation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cljest.reporter :as reporter]))

;; ---------------------------------------------------------------------------
;; Sample data
;; ---------------------------------------------------------------------------

(def sample-results
  [{:source-ns 'foo.bar :status :killed   :position [1 1] :operator-id :arith-plus-minus}
   {:source-ns 'foo.bar :status :survived :position [2 5] :operator-id :comp-lt-gt
    :original-form '(<)}
   {:source-ns 'foo.bar :status :killed   :position [3 1] :operator-id :logical-and-or}
   {:source-ns 'foo.baz :status :timed-out :position [1 1] :operator-id :arith-minus-plus}])

(def all-killed
  [{:source-ns 'x.y :status :killed :position [1 1] :operator-id :arith-plus-minus}
   {:source-ns 'x.y :status :killed :position [2 1] :operator-id :arith-plus-minus}])

(def all-survived
  [{:source-ns 'x.y :status :survived :position [1 1] :operator-id :arith-plus-minus}
   {:source-ns 'x.y :status :survived :position [2 1] :operator-id :comp-lt-gt}])

(def mixed-with-timeout
  [{:source-ns 'a.b :status :killed    :position [1 1] :operator-id :arith-plus-minus}
   {:source-ns 'a.b :status :survived  :position [2 1] :operator-id :comp-lt-gt}
   {:source-ns 'a.b :status :timed-out :position [3 1] :operator-id :logical-and-or}
   {:source-ns 'a.b :status :survived  :position [4 1] :operator-id :arith-minus-plus}])

;; ---------------------------------------------------------------------------
;; mutation-score
;; ---------------------------------------------------------------------------

(deftest mutation-score-empty-results
  (testing "empty results return 100.0 (vacuously passing)"
    (is (== 100.0 (reporter/mutation-score [])))))

(deftest mutation-score-all-killed
  (testing "all killed mutations yield 100.0"
    (is (== 100.0 (reporter/mutation-score all-killed)))))

(deftest mutation-score-all-survived
  (testing "all survived mutations yield 0.0"
    (is (== 0.0 (reporter/mutation-score all-survived)))))

(deftest mutation-score-mixed
  (testing "mixed results compute correct percentage"
    ;; sample-results: 2 killed + 1 timed-out = 3 effective killed, 1 survived, total 4
    ;; score = 75.0%
    (is (== 75.0 (reporter/mutation-score sample-results)))))

(deftest mutation-score-timed-out-counts-as-killed
  (testing ":timed-out mutations are treated as killed"
    ;; mixed-with-timeout: 1 killed + 1 timed-out = 2 effective, 2 survived, total 4
    ;; score = 50.0%
    (is (== 50.0 (reporter/mutation-score mixed-with-timeout)))))

;; ---------------------------------------------------------------------------
;; text-report
;; ---------------------------------------------------------------------------

(defn- make-report-data [results]
  {:results         results
   :duration-ns     5000000000  ; 5 seconds
   :source-ns-count 2
   :test-ns-count   2
   :config          {:threshold 80}})

(deftest text-report-contains-header
  (testing "text report contains the header banner"
    (let [output (with-out-str (reporter/text-report (make-report-data sample-results)))]
      (is (str/includes? output "CLJEST MUTATION TESTING REPORT")))))

(deftest text-report-contains-score
  (testing "text report contains the mutation score percentage"
    (let [output (with-out-str (reporter/text-report (make-report-data sample-results)))]
      (is (str/includes? output "75.0%")))))

(deftest text-report-contains-namespace-table
  (testing "text report shows namespace breakdown when multiple namespaces"
    (let [output (with-out-str (reporter/text-report (make-report-data sample-results)))]
      (is (str/includes? output "foo.bar"))
      (is (str/includes? output "foo.baz")))))

(deftest text-report-shows-survivors-section
  (testing "text report shows SURVIVORS section when survivors exist"
    (let [output (with-out-str (reporter/text-report (make-report-data sample-results)))]
      (is (str/includes? output "SURVIVORS"))
      (is (str/includes? output "comp-lt-gt")))))

(deftest text-report-shows-original-form-for-survivors
  (testing "text report displays original form for survived mutations"
    (let [output (with-out-str (reporter/text-report (make-report-data sample-results)))]
      (is (str/includes? output "(<)")))))

(deftest text-report-threshold-check-pass
  (testing "text report shows passing threshold check when score meets threshold"
    (let [report-data (make-report-data all-killed)
          output (with-out-str (reporter/text-report report-data))]
      (is (str/includes? output "meets threshold")))))

(deftest text-report-threshold-check-fail
  (testing "text report shows failing threshold check when score is below"
    (let [output (with-out-str (reporter/text-report (make-report-data all-survived)))]
      (is (str/includes? output "BELOW threshold")))))

(deftest text-report-empty-results
  (testing "text report handles empty results gracefully"
    (let [output (with-out-str
                   (reporter/text-report {:results         []
                                          :duration-ns     0
                                          :source-ns-count 0
                                          :test-ns-count   0
                                          :config          {:threshold 80}}))]
      (is (str/includes? output "CLJEST MUTATION TESTING REPORT"))
      ;; Empty results → score 100.0
      (is (str/includes? output "100.0%")))))

;; ---------------------------------------------------------------------------
;; html-report
;; ---------------------------------------------------------------------------

(deftest html-report-creates-file
  (testing "html-report creates an HTML file in the output directory"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                       "/cljest-html-" (System/nanoTime))
          path    (reporter/html-report (make-report-data sample-results) tmp-dir)]
      (try
        (is (.exists (io/file path)))
        (is (str/ends-with? path "mutation-report.html"))
        (finally
          (.delete (io/file path))
          (.delete (io/file tmp-dir)))))))

(deftest html-report-contains-doctype
  (testing "html report starts with <!DOCTYPE html>"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                       "/cljest-html-" (System/nanoTime))
          path    (reporter/html-report (make-report-data sample-results) tmp-dir)
          content (slurp path)]
      (try
        (is (str/starts-with? content "<!DOCTYPE html>"))
        (finally
          (.delete (io/file path))
          (.delete (io/file tmp-dir)))))))

(deftest html-report-contains-title
  (testing "html report contains the expected title"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                       "/cljest-html-" (System/nanoTime))
          path    (reporter/html-report (make-report-data sample-results) tmp-dir)
          content (slurp path)]
      (try
        (is (str/includes? content "cljest Mutation Testing Report"))
        (finally
          (.delete (io/file path))
          (.delete (io/file tmp-dir)))))))

(deftest html-report-contains-score-value
  (testing "html report contains the computed score"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                       "/cljest-html-" (System/nanoTime))
          path    (reporter/html-report (make-report-data sample-results) tmp-dir)
          content (slurp path)]
      (try
        (is (str/includes? content "75.0%"))
        (finally
          (.delete (io/file path))
          (.delete (io/file tmp-dir)))))))

(deftest html-report-is-complete
  (testing "html report contains closing </html> tag"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                       "/cljest-html-" (System/nanoTime))
          path    (reporter/html-report (make-report-data sample-results) tmp-dir)
          content (slurp path)]
      (try
        (is (str/includes? content "</html>"))
        (finally
          (.delete (io/file path))
          (.delete (io/file tmp-dir)))))))

(deftest html-report-contains-survivors
  (testing "html report includes surviving mutations section"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                       "/cljest-html-" (System/nanoTime))
          path    (reporter/html-report (make-report-data sample-results) tmp-dir)
          content (slurp path)]
      (try
        (is (str/includes? content "Surviving Mutations"))
        (is (str/includes? content "comp-lt-gt"))
        (finally
          (.delete (io/file path))
          (.delete (io/file tmp-dir)))))))

(deftest html-report-contains-namespace-rows
  (testing "html report includes per-namespace rows"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                       "/cljest-html-" (System/nanoTime))
          path    (reporter/html-report (make-report-data sample-results) tmp-dir)
          content (slurp path)]
      (try
        (is (str/includes? content "foo.bar"))
        (is (str/includes? content "foo.baz"))
        (finally
          (.delete (io/file path))
          (.delete (io/file tmp-dir)))))))
