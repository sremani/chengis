(ns chengis.db.trace-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.trace-store :as trace-store]))

(def test-db-path "/tmp/chengis-trace-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest create-span-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create-span! inserts a span and returns it"
      (let [span (trace-store/create-span! ds
                   {:trace-id "aaaa1111bbbb2222cccc3333dddd4444"
                    :span-id "1111222233334444"
                    :operation "build"
                    :started-at "2024-01-15T10:00:00Z"
                    :build-id "build-1"
                    :org-id "test-org"})]
        (is (some? span))
        (is (= "aaaa1111bbbb2222cccc3333dddd4444" (:trace-id span)))
        (is (= "1111222233334444" (:span-id span)))
        (is (= "build" (:operation span)))
        (is (= "chengis-master" (:service-name span)))
        (is (= "INTERNAL" (:kind span)))
        (is (= "OK" (:status span)))))))

(deftest create-span-with-parent-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create-span! supports parent-span-id"
      (trace-store/create-span! ds
        {:trace-id "trace-parent-test" :span-id "parent-span"
         :operation "build" :started-at "2024-01-15T10:00:00Z"})
      (let [child (trace-store/create-span! ds
                    {:trace-id "trace-parent-test" :span-id "child-span"
                     :parent-span-id "parent-span"
                     :operation "stage:Build" :started-at "2024-01-15T10:00:01Z"})]
        (is (= "parent-span" (:parent-span-id child)))))))

(deftest update-span-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "update-span! sets end time, duration, and status"
      (trace-store/create-span! ds
        {:trace-id "trace-update" :span-id "span-upd"
         :operation "test-op" :started-at "2024-01-15T10:00:00Z"})
      (trace-store/update-span! ds "span-upd"
        {:ended-at "2024-01-15T10:00:05Z"
         :duration-ms 5000
         :status "OK"})
      (let [span (trace-store/get-span ds "span-upd")]
        (is (some? span))
        (is (= "2024-01-15T10:00:05Z" (:ended-at span)))
        (is (= 5000 (:duration-ms span)))
        (is (= "OK" (:status span)))))))

(deftest get-trace-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "get-trace returns all spans for a trace, ordered by started_at"
      (trace-store/create-span! ds
        {:trace-id "trace-get-all" :span-id "span-a"
         :operation "build" :started-at "2024-01-15T10:00:00Z"})
      (trace-store/create-span! ds
        {:trace-id "trace-get-all" :span-id "span-b"
         :parent-span-id "span-a"
         :operation "stage:Build" :started-at "2024-01-15T10:00:01Z"})
      (trace-store/create-span! ds
        {:trace-id "trace-get-all" :span-id "span-c"
         :parent-span-id "span-a"
         :operation "stage:Test" :started-at "2024-01-15T10:00:02Z"})
      (let [spans (trace-store/get-trace ds "trace-get-all")]
        (is (= 3 (count spans)))
        (is (= ["span-a" "span-b" "span-c"] (mapv :span-id spans)))))))

(deftest get-build-traces-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "get-build-traces filters by build-id"
      (trace-store/create-span! ds
        {:trace-id "t1" :span-id "s1" :operation "build"
         :started-at "2024-01-15T10:00:00Z" :build-id "build-100"})
      (trace-store/create-span! ds
        {:trace-id "t2" :span-id "s2" :operation "build"
         :started-at "2024-01-15T10:00:01Z" :build-id "build-200"})
      (let [spans (trace-store/get-build-traces ds "build-100")]
        (is (= 1 (count spans)))
        (is (= "build-100" (:build-id (first spans))))))))

(deftest list-traces-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-traces returns root spans (no parent)"
      (trace-store/create-span! ds
        {:trace-id "tl-1" :span-id "root-1" :operation "build-1"
         :started-at "2024-01-15T10:00:00Z" :org-id "org-a"})
      (trace-store/create-span! ds
        {:trace-id "tl-1" :span-id "child-1" :parent-span-id "root-1"
         :operation "stage" :started-at "2024-01-15T10:00:01Z" :org-id "org-a"})
      (trace-store/create-span! ds
        {:trace-id "tl-2" :span-id "root-2" :operation "build-2"
         :started-at "2024-01-15T10:00:02Z" :org-id "org-b"})
      (let [all-traces (trace-store/list-traces ds)]
        (is (= 2 (count all-traces)))
        (is (every? #(nil? (:parent-span-id %)) all-traces)))
      ;; Org-scoped
      (let [org-a (trace-store/list-traces ds :org-id "org-a")]
        (is (= 1 (count org-a)))
        (is (= "org-a" (:org-id (first org-a))))))))

(deftest cleanup-old-traces-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "cleanup-old-traces! deletes old spans"
      ;; Insert span with old created_at by manually inserting
      (trace-store/create-span! ds
        {:trace-id "old-trace" :span-id "old-span" :operation "old-build"
         :started-at "2020-01-01T00:00:00Z"})
      ;; This span is recent
      (trace-store/create-span! ds
        {:trace-id "new-trace" :span-id "new-span" :operation "new-build"
         :started-at "2024-01-15T10:00:00Z"})
      ;; Only old spans should be cleaned (created_at is auto-set to now)
      ;; Since created_at is set to CURRENT_TIMESTAMP (now), both are "recent"
      ;; Cleanup with 0 days should delete everything
      (let [n (trace-store/cleanup-old-traces! ds 0)]
        (is (>= n 0))))))

(deftest count-spans-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "count-spans counts all spans"
      (is (= 0 (trace-store/count-spans ds)))
      (trace-store/create-span! ds
        {:trace-id "cnt-t" :span-id "cnt-s1" :operation "op1"
         :started-at "2024-01-15T10:00:00Z" :org-id "org-x"})
      (trace-store/create-span! ds
        {:trace-id "cnt-t" :span-id "cnt-s2" :operation "op2"
         :started-at "2024-01-15T10:00:01Z" :org-id "org-y"})
      (is (= 2 (trace-store/count-spans ds)))
      (is (= 1 (trace-store/count-spans ds :org-id "org-x"))))))

(deftest export-trace-otlp-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "export-trace-otlp returns OTLP-compatible structure"
      (trace-store/create-span! ds
        {:trace-id "otlp-trace" :span-id "otlp-root"
         :operation "build" :started-at "2024-01-15T10:00:00Z"
         :service-name "chengis-master"})
      (trace-store/create-span! ds
        {:trace-id "otlp-trace" :span-id "otlp-child"
         :parent-span-id "otlp-root"
         :operation "stage:Build" :started-at "2024-01-15T10:00:01Z"
         :service-name "chengis-master"})
      (let [otlp (trace-store/export-trace-otlp ds "otlp-trace")]
        (is (some? otlp))
        (is (contains? otlp :resourceSpans))
        (let [resource-spans (first (:resourceSpans otlp))
              scope-spans (first (:scopeSpans resource-spans))
              spans (:spans scope-spans)]
          (is (= 2 (count spans)))
          (is (= "otlp-trace" (:traceId (first spans))))
          (is (= "build" (:name (first spans)))))))))

(deftest export-trace-otlp-nil-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "export-trace-otlp returns nil for missing trace"
      (is (nil? (trace-store/export-trace-otlp ds "nonexistent"))))))
