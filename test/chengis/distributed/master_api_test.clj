(ns chengis.distributed.master-api-test
  (:require [clojure.test :refer :all]
            [chengis.distributed.master-api :as api]
            [chengis.distributed.agent-registry :as agent-reg]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(use-fixtures :each
  (fn [f]
    (agent-reg/reset-registry!)
    (f)
    (agent-reg/reset-registry!)))

(defn- make-req
  "Create a mock Ring request with a JSON body."
  [body & {:keys [path-params headers]}]
  (cond-> {:body (io/input-stream (.getBytes (json/write-str body)))}
    path-params (assoc :path-params path-params)
    headers     (assoc :headers headers)))

(defn- parse-response [resp]
  (json/read-str (:body resp) :key-fn keyword))

;; ---------------------------------------------------------------------------
;; Registration tests
;; ---------------------------------------------------------------------------

(deftest register-agent-handler-test
  (testing "registers an agent via API"
    (let [system {:config {}}
          handler (api/register-agent-handler system)
          resp (handler (make-req {:name "api-agent" :url "http://agent:9090"
                                   :labels ["docker"] :max-builds 3}))]
      (is (= 201 (:status resp)))
      (let [body (parse-response resp)]
        (is (some? (:agent-id body)))
        (is (= "api-agent" (:name body))))))

  (testing "rejects invalid JSON"
    (let [system {:config {}}
          handler (api/register-agent-handler system)
          resp (handler {:body (io/input-stream (.getBytes "not json"))})]
      (is (= 400 (:status resp))))))

;; ---------------------------------------------------------------------------
;; Heartbeat tests
;; ---------------------------------------------------------------------------

(deftest heartbeat-handler-test
  (testing "heartbeat for registered agent"
    (let [agent (agent-reg/register-agent! {:name "hb" :url "http://hb:9090"})
          system {:config {}}
          handler (api/heartbeat-handler system)
          resp (handler (make-req {} :path-params {:id (:id agent)}))]
      (is (= 200 (:status resp)))))

  (testing "heartbeat for unknown agent returns 404"
    (let [system {:config {}}
          handler (api/heartbeat-handler system)
          resp (handler (make-req {} :path-params {:id "nonexistent"}))]
      (is (= 404 (:status resp))))))

;; ---------------------------------------------------------------------------
;; Auth tests
;; ---------------------------------------------------------------------------

(deftest auth-test
  (testing "rejects unauthorized request when auth is configured"
    (let [system {:config {:distributed {:auth-token "secret123"}}}
          handler (api/register-agent-handler system)
          resp (handler (make-req {:name "agent" :url "http://a:9090"}))]
      (is (= 401 (:status resp)))))

  (testing "accepts authorized request"
    (let [system {:config {:distributed {:auth-token "secret123"}}}
          handler (api/register-agent-handler system)
          resp (handler (make-req {:name "agent" :url "http://a:9090"}
                          :headers {"authorization" "Bearer secret123"}))]
      (is (= 201 (:status resp))))))

;; ---------------------------------------------------------------------------
;; List agents
;; ---------------------------------------------------------------------------

(deftest list-agents-handler-test
  (testing "returns list of agents and summary"
    (agent-reg/register-agent! {:name "list-a1" :url "http://a1:9090"})
    (agent-reg/register-agent! {:name "list-a2" :url "http://a2:9090"})
    (let [handler (api/list-agents-handler {})
          resp (handler {})
          body (parse-response resp)]
      (is (= 200 (:status resp)))
      (is (= 2 (count (:agents body))))
      (is (= 2 (get-in body [:summary :total]))))))
