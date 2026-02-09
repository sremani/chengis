(ns chengis.web.health-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.web.handlers :as h]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-health-test.db")

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

(deftest health-check-test
  (let [ds (conn/create-datasource test-db-path)
        system {:config {:auth {:enabled false}} :db ds}
        handler (h/health-check system)]

    (testing "health endpoint returns 200 with JSON"
      (let [resp (handler {:uri "/health" :request-method :get})
            body (json/read-str (:body resp) :key-fn keyword)]
        (is (= 200 (:status resp)))
        (is (= "application/json" (get-in resp [:headers "Content-Type"])))
        (is (= "ok" (:status body)))
        (is (= "1.0.0" (:version body)))
        (is (number? (:uptime-seconds body)))
        ;; auth-enabled should NOT be exposed in health endpoint (security)
        (is (nil? (:auth-enabled body)))))))

(deftest readiness-check-test
  (let [ds (conn/create-datasource test-db-path)
        system {:config {} :db ds}
        handler (h/readiness-check system)]

    (testing "readiness returns 200 when DB is accessible"
      (let [resp (handler {:uri "/ready" :request-method :get})
            body (json/read-str (:body resp) :key-fn keyword)]
        (is (= 200 (:status resp)))
        (is (= "ready" (:status body)))
        (is (= "connected" (:database body)))))))

(deftest readiness-check-failure-test
  (testing "readiness returns 503 when DB is unavailable"
    (let [bad-system {:config {} :db nil}
          handler (h/readiness-check bad-system)
          resp (handler {:uri "/ready" :request-method :get})
          body (json/read-str (:body resp) :key-fn keyword)]
      (is (= 503 (:status resp)))
      (is (= "not-ready" (:status body))))))
