(ns chengis.db.log-chunk-store-test
  (:require [clojure.test :refer :all]
            [next.jdbc :as jdbc]
            [chengis.db.log-chunk-store :as lcs]
            [chengis.db.migrate :as migrate]))

(def ^:dynamic *ds* nil)

(defn- create-test-db []
  (let [db-path (str "/tmp/chengis-logchunk-test-" (System/currentTimeMillis) ".db")
        ds (jdbc/get-datasource (str "jdbc:sqlite:" db-path))]
    (migrate/migrate! db-path)
    {:ds ds :path db-path}))

(defn- cleanup-test-db [{:keys [path]}]
  (let [f (java.io.File. path)]
    (when (.exists f) (.delete f))))

(defmacro with-test-db [& body]
  `(let [db# (create-test-db)]
     (binding [*ds* (:ds db#)]
       (try ~@body
            (finally (cleanup-test-db db#))))))

(deftest save-and-get-chunks-test
  (with-test-db
    (testing "save and retrieve chunks"
      (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s1" :chunk-index 0
                              :source "stdout" :line-start 0 :line-count 10
                              :content "line 0\nline 1\nline 2"})
      (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s1" :chunk-index 1
                              :source "stdout" :line-start 10 :line-count 5
                              :content "line 10\nline 11"})
      (let [chunks (lcs/get-chunks *ds* "s1" :source "stdout")]
        (is (= 2 (count chunks)))
        (is (= 0 (:chunk-index (first chunks))))
        (is (= 1 (:chunk-index (second chunks))))))))

(deftest get-chunks-pagination-test
  (with-test-db
    (testing "paginate through chunks"
      (doseq [i (range 10)]
        (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s1" :chunk-index i
                                :source "stdout" :line-start (* i 100)
                                :line-count 100
                                :content (str "chunk-" i)}))
      (let [page1 (lcs/get-chunks *ds* "s1" :source "stdout" :offset 0 :limit 3)
            page2 (lcs/get-chunks *ds* "s1" :source "stdout" :offset 3 :limit 3)]
        (is (= 3 (count page1)))
        (is (= 3 (count page2)))
        (is (= 0 (:chunk-index (first page1))))
        (is (= 3 (:chunk-index (first page2))))))))

(deftest chunk-count-test
  (with-test-db
    (testing "count chunks"
      (doseq [i (range 5)]
        (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s1" :chunk-index i
                                :source "stdout" :line-start (* i 10)
                                :line-count 10 :content "data"}))
      (is (= 5 (lcs/get-chunk-count *ds* "s1")))
      (is (= 5 (lcs/get-chunk-count *ds* "s1" :source "stdout")))
      (is (= 0 (lcs/get-chunk-count *ds* "s1" :source "stderr"))))))

(deftest total-line-count-test
  (with-test-db
    (testing "sum line counts"
      (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s1" :chunk-index 0
                              :source "stdout" :line-start 0 :line-count 100
                              :content "data"})
      (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s1" :chunk-index 1
                              :source "stdout" :line-start 100 :line-count 50
                              :content "data"})
      (is (= 150 (lcs/get-total-line-count *ds* "s1")))
      (is (= 150 (lcs/get-total-line-count *ds* "s1" :source "stdout")))
      (is (= 0 (lcs/get-total-line-count *ds* "s1" :source "stderr"))))))

(deftest separate-sources-test
  (with-test-db
    (testing "stdout and stderr stored separately"
      (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s1" :chunk-index 0
                              :source "stdout" :line-start 0 :line-count 10
                              :content "stdout data"})
      (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s1" :chunk-index 0
                              :source "stderr" :line-start 0 :line-count 5
                              :content "stderr data"})
      (is (= 1 (lcs/get-chunk-count *ds* "s1" :source "stdout")))
      (is (= 1 (lcs/get-chunk-count *ds* "s1" :source "stderr")))
      (is (= 2 (lcs/get-chunk-count *ds* "s1"))))))

(deftest delete-chunks-test
  (with-test-db
    (testing "delete all chunks for a build"
      (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s1" :chunk-index 0
                              :source "stdout" :line-start 0 :line-count 10
                              :content "data"})
      (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s2" :chunk-index 0
                              :source "stdout" :line-start 0 :line-count 10
                              :content "data"})
      (lcs/delete-chunks-for-build! *ds* "b1")
      (is (= 0 (lcs/get-chunk-count *ds* "s1")))
      (is (= 0 (lcs/get-chunk-count *ds* "s2"))))))

(deftest get-chunks-for-build-test
  (with-test-db
    (testing "get all chunks for a build"
      (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s1" :chunk-index 0
                              :source "stdout" :line-start 0 :line-count 10
                              :content "step1 data"})
      (lcs/save-chunk! *ds* {:build-id "b1" :step-id "s2" :chunk-index 0
                              :source "stdout" :line-start 0 :line-count 10
                              :content "step2 data"})
      (let [chunks (lcs/get-chunks-for-build *ds* "b1")]
        (is (= 2 (count chunks)))))))

(deftest empty-step-test
  (with-test-db
    (testing "empty step returns empty results"
      (is (= 0 (lcs/get-chunk-count *ds* "nonexistent")))
      (is (= 0 (lcs/get-total-line-count *ds* "nonexistent")))
      (is (empty? (lcs/get-chunks *ds* "nonexistent"))))))
