(ns chengis.distributed.master-api
  "Ring handlers for the distributed build master API.
   Agents communicate with the master through these endpoints:
     POST /api/agents/register     — agent registration
     POST /api/agents/:id/heartbeat — heartbeat
     POST /api/builds/:id/events   — ingest build events from agents
     POST /api/builds/:id/result   — ingest build result from agents"
  (:require [chengis.distributed.agent-registry :as agent-reg]
            [chengis.db.build-store :as build-store]
            [chengis.engine.events :as events]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Auth middleware (simple shared-secret)
;; ---------------------------------------------------------------------------

(defn- check-auth
  "Validate the auth token from request headers."
  [req system]
  (let [expected (get-in system [:config :distributed :auth-token])
        provided (some-> (get-in req [:headers "authorization"])
                         (clojure.string/replace #"^Bearer " ""))]
    (or (nil? expected) ;; No auth required if no token configured
        (= expected provided))))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-str body)})

(defn- parse-json-body [req]
  (try
    (when-let [body (:body req)]
      (json/read-str (slurp body) :key-fn keyword))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn register-agent-handler
  "POST /api/agents/register — Register a new agent."
  [system]
  (fn [req]
    (if-not (check-auth req system)
      (json-response 401 {:error "Unauthorized"})
      (let [body (parse-json-body req)]
        (if-not body
          (json-response 400 {:error "Invalid JSON body"})
          (let [agent (agent-reg/register-agent! body)]
            (json-response 201 {:agent-id (:id agent)
                                :name (:name agent)
                                :status "registered"})))))))

(defn heartbeat-handler
  "POST /api/agents/:id/heartbeat — Agent heartbeat."
  [system]
  (fn [req]
    (if-not (check-auth req system)
      (json-response 401 {:error "Unauthorized"})
      (let [agent-id (get-in req [:path-params :id])
            body (parse-json-body req)]
        (if (agent-reg/heartbeat! agent-id body)
          (json-response 200 {:status "ok"})
          (json-response 404 {:error "Agent not found"}))))))

(defn ingest-event-handler
  "POST /api/builds/:id/events — Receive build events from agents."
  [system]
  (fn [req]
    (if-not (check-auth req system)
      (json-response 401 {:error "Unauthorized"})
      (let [build-id (get-in req [:path-params :id])
            body (parse-json-body req)]
        (when body
          ;; Feed event into the SSE event bus
          (events/publish! build-id body))
        (json-response 200 {:status "ok"})))))

(defn ingest-result-handler
  "POST /api/builds/:id/result — Receive final build result from agents."
  [system]
  (fn [req]
    (if-not (check-auth req system)
      (json-response 401 {:error "Unauthorized"})
      (let [build-id (get-in req [:path-params :id])
            body (parse-json-body req)]
        (if-not body
          (json-response 400 {:error "Invalid JSON body"})
          (do
            (log/info "Received build result from agent for" build-id
                      "status:" (:build-status body))
            ;; Persist build result
            (when-let [ds (:db system)]
              (try
                (build-store/save-build-result! ds (assoc body :build-id build-id))
                (catch Exception e
                  (log/error "Failed to persist agent build result:" (.getMessage e)))))
            ;; Decrement agent build count
            (when-let [agent-id (:agent-id body)]
              (agent-reg/decrement-builds! agent-id))
            ;; Publish completion event
            (events/publish! build-id
              {:event-type :build-completed
               :build-id build-id
               :data {:build-status (:build-status body)}})
            (json-response 200 {:status "ok"})))))))

(defn list-agents-handler
  "GET /api/agents — List all registered agents."
  [_system]
  (fn [_req]
    (json-response 200 {:agents (agent-reg/list-agents)
                        :summary (agent-reg/registry-summary)})))
