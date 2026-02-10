(ns chengis.engine.tracing-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.trace-store :as trace-store]
            [chengis.engine.tracing :as tracing]))

(def test-db-path "/tmp/chengis-tracing-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-system
  "Build a test system map with tracing enabled."
  [& {:keys [tracing-enabled] :or {tracing-enabled true}}]
  (let [ds (conn/create-datasource test-db-path)]
    {:db ds
     :config {:feature-flags {:tracing tracing-enabled}
              :tracing {:sample-rate 1.0 :retention-days 7}}
     :metrics nil}))

;; ---------------------------------------------------------------------------
;; ID Generation
;; ---------------------------------------------------------------------------

(deftest generate-trace-id-test
  (testing "generate-trace-id produces 32-char hex string"
    (let [id (tracing/generate-trace-id)]
      (is (string? id))
      (is (= 32 (count id)))
      (is (re-matches #"[0-9a-f]+" id))))

  (testing "generate-trace-id produces unique IDs"
    (let [ids (repeatedly 100 tracing/generate-trace-id)]
      (is (= 100 (count (set ids)))))))

(deftest generate-span-id-test
  (testing "generate-span-id produces 16-char hex string"
    (let [id (tracing/generate-span-id)]
      (is (string? id))
      (is (= 16 (count id)))
      (is (re-matches #"[0-9a-f]+" id)))))

;; ---------------------------------------------------------------------------
;; Sampling
;; ---------------------------------------------------------------------------

(deftest should-sample-test
  (testing "sample-rate 1.0 always samples"
    (let [config {:tracing {:sample-rate 1.0}}]
      (is (every? true? (repeatedly 100 #(tracing/should-sample? config))))))

  (testing "sample-rate 0.0 never samples"
    (let [config {:tracing {:sample-rate 0.0}}]
      (is (every? false? (repeatedly 100 #(tracing/should-sample? config)))))))

;; ---------------------------------------------------------------------------
;; Span lifecycle
;; ---------------------------------------------------------------------------

(deftest start-span-test
  (let [system (test-system)]
    (testing "start-span! creates a span in DB"
      (let [span (tracing/start-span! system
                   {:operation "build"
                    :build-id "test-build-1"
                    :org-id "test-org"})]
        (is (some? span))
        (is (some? (:span-id span)))
        (is (some? (:trace-id span)))
        (is (= "build" (:operation span)))
        (is (= "test-build-1" (:build-id span)))
        ;; Verify it was actually persisted
        (let [db-span (trace-store/get-span (:db system) (:span-id span))]
          (is (some? db-span))
          (is (= "build" (:operation db-span))))))))

(deftest start-span-with-parent-test
  (let [system (test-system)]
    (testing "start-span! links to parent"
      (let [parent (tracing/start-span! system
                     {:operation "build" :org-id "org-1"})
            child (tracing/start-span! system
                    {:trace-id (:trace-id parent)
                     :parent-span-id (:span-id parent)
                     :operation "stage:Build"
                     :org-id "org-1"})]
        (is (= (:trace-id parent) (:trace-id child)))
        (is (= (:span-id parent) (:parent-span-id child)))))))

(deftest start-span-disabled-test
  (let [system (test-system :tracing-enabled false)]
    (testing "start-span! returns nil when tracing disabled"
      (let [span (tracing/start-span! system
                   {:operation "build" :org-id "test-org"})]
        (is (nil? span))))))

(deftest end-span-test
  (let [system (test-system)]
    (testing "end-span! updates span with duration and end time"
      (let [span (tracing/start-span! system
                   {:operation "test-end" :org-id "org-1"})]
        (Thread/sleep 10)
        (tracing/end-span! system span)
        (let [updated (trace-store/get-span (:db system) (:span-id span))]
          (is (some? (:ended-at updated)))
          (is (pos? (:duration-ms updated)))
          (is (= "OK" (:status updated))))))))

(deftest end-span-error-test
  (let [system (test-system)]
    (testing "end-span! records ERROR status"
      (let [span (tracing/start-span! system
                   {:operation "test-error" :org-id "org-1"})]
        (tracing/end-span! system span :status "ERROR")
        (let [updated (trace-store/get-span (:db system) (:span-id span))]
          (is (= "ERROR" (:status updated))))))))

(deftest end-span-nil-test
  (testing "end-span! gracefully handles nil span"
    (let [system (test-system)]
      ;; Should not throw
      (tracing/end-span! system nil))))

;; ---------------------------------------------------------------------------
;; with-span macro
;; ---------------------------------------------------------------------------

(deftest with-span-success-test
  (let [system (test-system)]
    (testing "with-span completes span on success"
      (let [result (tracing/with-span [span system {:operation "macro-test" :org-id "org-1"}]
                     (is (some? span))
                     42)]
        (is (= 42 result))))))

(deftest with-span-error-test
  (let [system (test-system)]
    (testing "with-span marks ERROR on exception and re-throws"
      (is (thrown? Exception
            (tracing/with-span [span system {:operation "macro-error" :org-id "org-1"}]
              (throw (Exception. "test error"))))))))

;; ---------------------------------------------------------------------------
;; Query helpers
;; ---------------------------------------------------------------------------

(deftest get-trace-test
  (let [system (test-system)]
    (testing "get-trace returns spans for a trace"
      (let [span (tracing/start-span! system
                   {:operation "build" :org-id "org-1"})
            trace-id (:trace-id span)]
        (tracing/start-span! system
          {:trace-id trace-id
           :parent-span-id (:span-id span)
           :operation "stage:Build"
           :org-id "org-1"})
        (let [spans (tracing/get-trace system trace-id)]
          (is (= 2 (count spans))))))))

(deftest list-traces-test
  (let [system (test-system)]
    (testing "list-traces returns root spans"
      (tracing/start-span! system
        {:operation "build-1" :org-id "org-a"})
      (tracing/start-span! system
        {:operation "build-2" :org-id "org-a"})
      (let [traces (tracing/list-traces system :org-id "org-a")]
        (is (= 2 (count traces)))
        (is (every? #(nil? (:parent-span-id %)) traces))))))

(deftest export-otlp-test
  (let [system (test-system)]
    (testing "export-otlp returns OTLP structure"
      (let [span (tracing/start-span! system
                   {:operation "build" :org-id "org-1"})
            otlp (tracing/export-otlp system (:trace-id span))]
        (is (some? otlp))
        (is (contains? otlp :resourceSpans))))))
