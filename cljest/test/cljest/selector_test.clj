(ns cljest.selector-test
  "Tests for the namespace selector module — filtering, matching, and
   namespace-to-file resolution."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cljest.selector :as selector]))

;; ---------------------------------------------------------------------------
;; filter-namespaces
;; ---------------------------------------------------------------------------

(deftest filter-namespaces-include-pattern
  (testing "include pattern keeps only matching namespaces"
    (let [nses  ['foo.bar 'foo.baz 'other.thing]
          config {:namespaces [#"foo\..*"]}]
      (is (= ['foo.bar 'foo.baz]
             (vec (selector/filter-namespaces nses config)))))))

(deftest filter-namespaces-exclude-pattern
  (testing "exclude pattern removes matching namespaces"
    (let [nses   ['foo.bar 'foo.baz]
          config {:exclude-namespaces [#".*baz"]}]
      (is (= ['foo.bar]
             (vec (selector/filter-namespaces nses config)))))))

(deftest filter-namespaces-no-patterns
  (testing "no patterns returns all namespaces unchanged"
    (let [nses   ['a.b 'c.d 'e.f]
          config {}]
      (is (= ['a.b 'c.d 'e.f]
             (vec (selector/filter-namespaces nses config)))))))

(deftest filter-namespaces-include-and-exclude
  (testing "include + exclude patterns work together"
    (let [nses   ['foo.bar 'foo.baz 'other.thing]
          config {:namespaces        [#"foo\..*"]
                  :exclude-namespaces [#".*baz"]}]
      (is (= ['foo.bar]
             (vec (selector/filter-namespaces nses config)))))))

(deftest filter-namespaces-no-match
  (testing "include pattern that matches nothing returns empty"
    (let [nses   ['a.b 'c.d]
          config {:namespaces [#"zzz\..*"]}]
      (is (empty? (selector/filter-namespaces nses config))))))

;; ---------------------------------------------------------------------------
;; match-source-to-tests
;; ---------------------------------------------------------------------------

(deftest match-source-to-tests-finds-matching-test-ns
  (testing "finds foo.bar-test from available test namespaces"
    (is (= ['foo.bar-test]
           (selector/match-source-to-tests 'foo.bar
                                           #{'foo.bar-test 'foo.baz-test})))))

(deftest match-source-to-tests-empty-when-no-match
  (testing "returns empty vec when no test namespace matches"
    (is (= []
           (selector/match-source-to-tests 'foo.bar
                                           #{'unrelated.test 'other.test})))))

(deftest match-source-to-tests-matches-dash-tests-suffix
  (testing "matches foo.bar-tests (plural) suffix"
    (is (= ['foo.bar-tests]
           (selector/match-source-to-tests 'foo.bar
                                           #{'foo.bar-tests})))))

(deftest match-source-to-tests-matches-properties-test
  (testing "matches foo.bar-properties-test suffix"
    (is (= ['foo.bar-properties-test]
           (selector/match-source-to-tests 'foo.bar
                                           #{'foo.bar-properties-test})))))

(deftest match-source-to-tests-multiple-matches
  (testing "returns all matching test namespaces when multiple exist"
    (let [result (selector/match-source-to-tests
                   'foo.bar
                   #{'foo.bar-test 'foo.bar-tests 'foo.bar-properties-test})]
      (is (= 3 (count result)))
      (is (every? #{'foo.bar-test 'foo.bar-tests 'foo.bar-properties-test}
                  result)))))

;; ---------------------------------------------------------------------------
;; namespace-to-file
;; ---------------------------------------------------------------------------

(deftest namespace-to-file-finds-existing-file
  (testing "resolves namespace symbol to file path in a temp directory"
    (let [tmp-dir  (str (System/getProperty "java.io.tmpdir")
                        "/cljest-test-" (System/nanoTime))
          ns-file  (str tmp-dir "/foo/bar.clj")]
      (.mkdirs (io/file tmp-dir "foo"))
      (spit ns-file "(ns foo.bar)")
      (try
        (is (= (.getAbsolutePath (io/file ns-file))
               (selector/namespace-to-file 'foo.bar [tmp-dir])))
        (finally
          (.delete (io/file ns-file))
          (.delete (io/file tmp-dir "foo"))
          (.delete (io/file tmp-dir)))))))

(deftest namespace-to-file-nil-when-missing
  (testing "returns nil when file does not exist on disk"
    (is (nil? (selector/namespace-to-file 'does.not.exist
                                          ["/tmp/nonexistent-path"])))))

(deftest namespace-to-file-converts-hyphens-to-underscores
  (testing "namespace with hyphens maps to underscored file path"
    (let [tmp-dir  (str (System/getProperty "java.io.tmpdir")
                        "/cljest-test-" (System/nanoTime))
          ns-file  (str tmp-dir "/foo/bar_baz.clj")]
      (.mkdirs (io/file tmp-dir "foo"))
      (spit ns-file "(ns foo.bar-baz)")
      (try
        (is (= (.getAbsolutePath (io/file ns-file))
               (selector/namespace-to-file 'foo.bar-baz [tmp-dir])))
        (finally
          (.delete (io/file ns-file))
          (.delete (io/file tmp-dir "foo"))
          (.delete (io/file tmp-dir)))))))

;; ---------------------------------------------------------------------------
;; discover-namespaces (with temp dir)
;; ---------------------------------------------------------------------------

(deftest discover-namespaces-with-temp-directory
  (testing "discovers namespaces in a temporary source tree"
    (let [tmp-dir  (str (System/getProperty "java.io.tmpdir")
                        "/cljest-discover-" (System/nanoTime))
          ns-file  (str tmp-dir "/my/sample.clj")]
      (.mkdirs (io/file tmp-dir "my"))
      (spit ns-file "(ns my.sample)")
      (try
        (let [found (selector/discover-namespaces [tmp-dir])]
          (is (contains? found 'my.sample)))
        (finally
          (.delete (io/file ns-file))
          (.delete (io/file tmp-dir "my"))
          (.delete (io/file tmp-dir)))))))

(deftest discover-namespaces-nonexistent-path
  (testing "non-existent paths are silently skipped"
    (is (empty? (selector/discover-namespaces ["/tmp/no-such-dir-ever"])))))
