(ns chengis.distributed.master-api-test
  (:require [clojure.test :refer :all]
            [chengis.distributed.master-api :as api]
            [chengis.distributed.agent-registry :as agent-reg]
            [chengis.web.server :as server]
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
          system {:config {:distributed {:auth-token "test-token"}}}
          handler (api/heartbeat-handler system)
          resp (handler (make-req {} :path-params {:id (:id agent)}
                          :headers {"authorization" "Bearer test-token"}))]
      (is (= 200 (:status resp)))))

  (testing "heartbeat for unknown agent returns 404"
    (let [system {:config {:distributed {:auth-token "test-token"}}}
          handler (api/heartbeat-handler system)
          resp (handler (make-req {} :path-params {:id "nonexistent"}
                          :headers {"authorization" "Bearer test-token"}))]
      (is (= 404 (:status resp))))))

;; ---------------------------------------------------------------------------
;; Auth tests
;; ---------------------------------------------------------------------------

(deftest auth-test
  ;; register-agent-handler relies on wrap-require-role at the route level,
  ;; so handler-level auth is tested via heartbeat-handler instead.

  (testing "rejects unauthorized heartbeat when auth is configured"
    (let [agent (agent-reg/register-agent! {:name "auth-agent" :url "http://a:9090"})
          system {:config {:distributed {:auth-token "secret123"}}}
          handler (api/heartbeat-handler system)
          resp (handler (make-req {} :path-params {:id (:id agent)}))]
      (is (= 401 (:status resp)))))

  (testing "accepts authorized heartbeat"
    (let [agent (agent-reg/register-agent! {:name "auth-agent2" :url "http://a:9090"})
          system {:config {:distributed {:auth-token "secret123"}}}
          handler (api/heartbeat-handler system)
          resp (handler (make-req {} :path-params {:id (:id agent)}
                          :headers {"authorization" "Bearer secret123"}))]
      (is (= 200 (:status resp)))))

  (testing "register handler accepts any request (auth is at route level)"
    (let [system {:config {:distributed {:auth-token "secret123"}}}
          handler (api/register-agent-handler system)
          resp (handler (make-req {:name "agent" :url "http://a:9090"}))]
      (is (= 201 (:status resp))))))

;; ---------------------------------------------------------------------------
;; List agents
;; ---------------------------------------------------------------------------

(deftest list-agents-handler-test
  (testing "returns list of agents and summary"
    (agent-reg/register-agent! {:name "list-a1" :url "http://a1:9090"})
    (agent-reg/register-agent! {:name "list-a2" :url "http://a2:9090"})
    (let [system {:config {:distributed {:auth-token "test-token"}}}
          handler (api/list-agents-handler system)
          resp (handler {:headers {"authorization" "Bearer test-token"}})
          body (parse-response resp)]
      (is (= 200 (:status resp)))
      (is (= 2 (count (:agents body))))
      (is (= 2 (get-in body [:summary :total]))))))

;; ---------------------------------------------------------------------------
;; CQ-03: Nil auth token must reject requests
;; ---------------------------------------------------------------------------

(deftest nil-token-rejects-request-test
  (testing "heartbeat rejects request when auth-token is nil"
    (let [agent (agent-reg/register-agent! {:name "nil-auth" :url "http://a:9090"})
          system {:config {:distributed {}}}  ;; no :auth-token
          handler (api/heartbeat-handler system)
          resp (handler (make-req {} :path-params {:id (:id agent)}))]
      (is (= 401 (:status resp))
          "Nil auth-token must reject all requests")))

  (testing "heartbeat rejects request when auth-token is empty string"
    (let [agent (agent-reg/register-agent! {:name "empty-auth" :url "http://a:9090"})
          system {:config {:distributed {:auth-token ""}}}
          handler (api/heartbeat-handler system)
          resp (handler (make-req {} :path-params {:id (:id agent)}))]
      (is (= 401 (:status resp))
          "Empty auth-token must reject all requests")))

  (testing "heartbeat rejects request when auth-token is whitespace-only"
    (let [agent (agent-reg/register-agent! {:name "ws-auth" :url "http://a:9090"})
          system {:config {:distributed {:auth-token "   "}}}
          handler (api/heartbeat-handler system)
          resp (handler (make-req {} :path-params {:id (:id agent)}
                          :headers {"authorization" "Bearer    "}))]
      (is (= 401 (:status resp))
          "Whitespace-only auth-token must reject all requests")))

  (testing "heartbeat accepts request with valid matching token"
    (let [agent (agent-reg/register-agent! {:name "valid-auth" :url "http://a:9090"})
          system {:config {:distributed {:auth-token "my-secret"}}}
          handler (api/heartbeat-handler system)
          resp (handler (make-req {} :path-params {:id (:id agent)}
                          :headers {"authorization" "Bearer my-secret"}))]
      (is (= 200 (:status resp))
          "Matching auth-token must accept request"))))

;; ---------------------------------------------------------------------------
;; CQ-03: validate-config! rejects distributed mode without auth-token
;; ---------------------------------------------------------------------------

(deftest distributed-mode-requires-auth-token-test
  (testing "validate-config! throws when distributed enabled + no auth-token"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"auth-token"
          (server/validate-config! {:distributed {:enabled true}}))))

  (testing "validate-config! throws when distributed enabled + empty auth-token"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"auth-token"
          (server/validate-config! {:distributed {:enabled true :auth-token ""}}))))

  (testing "validate-config! throws when distributed enabled + whitespace-only auth-token"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"auth-token"
          (server/validate-config! {:distributed {:enabled true :auth-token "   "}}))))

  (testing "validate-config! passes when distributed enabled + token set"
    (is (nil? (server/validate-config!
                {:distributed {:enabled true :auth-token "secret"}}))))

  (testing "validate-config! passes when distributed disabled"
    (is (nil? (server/validate-config!
                {:distributed {:enabled false}})))))
