(ns chengis.db.webhook-log-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.webhook-log :as webhook-log]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-webhook-log-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

(deftest log-and-query-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "log a webhook event"
      (let [id (webhook-log/log-webhook-event! ds
                 {:provider :github
                  :event-type "push"
                  :repo-url "https://github.com/foo/bar.git"
                  :repo-name "foo/bar"
                  :branch "main"
                  :commit-sha "abc123"
                  :signature-valid true
                  :status "processed"
                  :matched-jobs 2
                  :triggered-builds 2
                  :payload-size 1234
                  :processing-ms 15})]
        (is (some? id))))

    (testing "list webhook events"
      (let [events (webhook-log/list-webhook-events ds)]
        (is (= 1 (count events)))
        (is (= "github" (:provider (first events))))
        (is (= "push" (:event-type (first events))))
        (is (= "foo/bar" (:repo-name (first events))))
        (is (= 2 (:triggered-builds (first events))))
        (is (= 15 (:processing-ms (first events))))))

    (testing "count webhook events"
      (is (= 1 (webhook-log/count-webhook-events ds)))
      (is (= 1 (webhook-log/count-webhook-events ds :provider "github")))
      (is (= 0 (webhook-log/count-webhook-events ds :provider "gitlab"))))

    (testing "get single event"
      (let [events (webhook-log/list-webhook-events ds)
            event (webhook-log/get-webhook-event ds (:id (first events)))]
        (is (some? event))
        (is (= "github" (:provider event)))))

    (testing "log rejected event"
      (webhook-log/log-webhook-event! ds
        {:provider :gitlab
         :event-type "push"
         :signature-valid false
         :status "rejected"
         :error "Invalid signature"
         :payload-size 500
         :processing-ms 1})
      (is (= 2 (webhook-log/count-webhook-events ds)))
      (is (= 1 (webhook-log/count-webhook-events ds :status "rejected"))))

    (testing "pagination"
      ;; Add more events
      (dotimes [_ 5]
        (webhook-log/log-webhook-event! ds
          {:provider :github :status "processed" :payload-size 100}))
      (is (= 7 (webhook-log/count-webhook-events ds)))
      (let [page1 (webhook-log/list-webhook-events ds :limit 3 :offset 0)
            page2 (webhook-log/list-webhook-events ds :limit 3 :offset 3)]
        (is (= 3 (count page1)))
        (is (= 3 (count page2)))))

    (testing "cleanup old events"
      ;; All events were just created — they're not old enough to purge
      (let [cleaned (webhook-log/cleanup-old-events! ds 1)]
        (is (zero? cleaned))
        (is (= 7 (webhook-log/count-webhook-events ds)))))))
