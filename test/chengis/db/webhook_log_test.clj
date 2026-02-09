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
        (is (= 7 (webhook-log/count-webhook-events ds)))))

    (testing "org-id attribution and org-scoped queries"
      (let [org-a-id "org-alpha"
            org-b-id "org-beta"]
        ;; Log events with org-id attribution
        (webhook-log/log-webhook-event! ds
          {:provider :github :event-type "push" :status "processed"
           :repo-url "https://github.com/orgA/repo.git"
           :repo-name "orgA/repo" :branch "main"
           :matched-jobs 1 :triggered-builds 1
           :org-id org-a-id :payload-size 200})
        (webhook-log/log-webhook-event! ds
          {:provider :gitlab :event-type "push" :status "processed"
           :repo-url "https://gitlab.com/orgB/repo.git"
           :repo-name "orgB/repo" :branch "develop"
           :matched-jobs 1 :triggered-builds 1
           :org-id org-b-id :payload-size 300})
        ;; Org-scoped list should only return that org's events
        (let [org-a-events (webhook-log/list-webhook-events ds :org-id org-a-id)
              org-b-events (webhook-log/list-webhook-events ds :org-id org-b-id)]
          (is (= 1 (count org-a-events))
              "org-alpha should see exactly 1 event")
          (is (= "orgA/repo" (:repo-name (first org-a-events))))
          (is (= 1 (count org-b-events))
              "org-beta should see exactly 1 event")
          (is (= "orgB/repo" (:repo-name (first org-b-events)))))
        ;; Org-scoped count
        (is (= 1 (webhook-log/count-webhook-events ds :org-id org-a-id)))
        (is (= 1 (webhook-log/count-webhook-events ds :org-id org-b-id)))))))
