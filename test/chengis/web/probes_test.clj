(ns chengis.web.probes-test
  "Tests for enhanced health, readiness, and startup probes (Phase 3c)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.web.handlers :as h]
            [chengis.distributed.agent-registry :as agent-reg]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-probes-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  ;; Reset startup state for each test
  (h/reset-startup-state!)
  ;; Reset agent registry
  (agent-reg/reset-registry!)
  (f)
  ;; Cleanup
  (h/reset-startup-state!)
  (agent-reg/reset-registry!)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest health-check-includes-instance-id-test
  (testing "health-check includes instance-id from HA config"
    (let [ds (conn/create-datasource test-db-path)
          system {:config {:ha {:instance-id "pod-master-0"}} :db ds}
          handler (h/health-check system)
          resp (handler {:uri "/health" :request-method :get})
          body (json/read-str (:body resp) :key-fn keyword)]
      (is (= 200 (:status resp)))
      (is (= "pod-master-0" (:instance-id body))
          "instance-id should come from HA config")))

  (testing "health-check shows 'standalone' when no instance-id configured"
    (let [ds (conn/create-datasource test-db-path)
          system {:config {} :db ds}
          handler (h/health-check system)
          resp (handler {:uri "/health" :request-method :get})
          body (json/read-str (:body resp) :key-fn keyword)]
      (is (= "standalone" (:instance-id body))
          "Should default to 'standalone' when no HA config"))))

(deftest readiness-check-includes-agents-test
  (testing "readiness-check includes agent summary"
    (let [ds (conn/create-datasource test-db-path)
          system {:config {} :db ds}
          handler (h/readiness-check system)]
      ;; Register some agents
      (agent-reg/register-agent!
        {:name "agent-1" :url "http://agent1:9090" :max-builds 3})
      (agent-reg/register-agent!
        {:name "agent-2" :url "http://agent2:9090" :max-builds 2})
      (let [resp (handler {:uri "/ready" :request-method :get})
            body (json/read-str (:body resp) :key-fn keyword)]
        (is (= 200 (:status resp)))
        (is (= "ready" (:status body)))
        (is (some? (:agents body))
            "Should include agents summary")
        (is (= 2 (get-in body [:agents :total]))
            "Should show 2 total agents")
        (is (= 2 (get-in body [:agents :online]))
            "Should show 2 online agents")
        (is (= 5 (get-in body [:agents :total-capacity]))
            "Should show total capacity of 5")))))

(deftest readiness-check-includes-queue-depth-test
  (testing "readiness-check includes queue-depth when queue is enabled"
    (let [ds (conn/create-datasource test-db-path)
          system {:config {:distributed {:enabled true
                                         :dispatch {:queue-enabled true}}}
                  :db ds}
          handler (h/readiness-check system)
          resp (handler {:uri "/ready" :request-method :get})
          body (json/read-str (:body resp) :key-fn keyword)]
      (is (= 200 (:status resp)))
      (is (some? (:queue-depth body))
          "Should include queue-depth when queue enabled")
      (is (= 0 (:queue-depth body))
          "Queue depth should be 0 with no pending items")))

  (testing "readiness-check omits queue-depth when queue is not enabled"
    (let [ds (conn/create-datasource test-db-path)
          system {:config {:distributed {:enabled false}} :db ds}
          handler (h/readiness-check system)
          resp (handler {:uri "/ready" :request-method :get})
          body (json/read-str (:body resp) :key-fn keyword)]
      (is (nil? (:queue-depth body))
          "Should not include queue-depth when queue not enabled"))))

(deftest startup-check-before-complete-test
  (testing "startup-check returns 503 before mark-startup-complete!"
    (let [system {:config {} :db nil}
          handler (h/startup-check system)
          resp (handler {:uri "/startup" :request-method :get})
          body (json/read-str (:body resp) :key-fn keyword)]
      (is (= 503 (:status resp))
          "Should return 503 before startup is complete")
      (is (= "starting" (:status body))))))

(deftest startup-check-after-complete-test
  (testing "startup-check returns 200 after mark-startup-complete!"
    (h/mark-startup-complete!)
    (let [system {:config {} :db nil}
          handler (h/startup-check system)
          resp (handler {:uri "/startup" :request-method :get})
          body (json/read-str (:body resp) :key-fn keyword)]
      (is (= 200 (:status resp))
          "Should return 200 after startup is complete")
      (is (= "started" (:status body))))))
