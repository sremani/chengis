(ns chengis.distributed.master-api
  "Ring handlers for the distributed build master API.
   Agents communicate with the master through these endpoints:
     POST /api/agents/register     — agent registration
     POST /api/agents/:id/heartbeat — heartbeat
     POST /api/builds/:id/events   — ingest build events from agents
     POST /api/builds/:id/result   — ingest build result from agents"
  (:require [chengis.distributed.agent-registry :as agent-reg]
            [chengis.distributed.build-queue :as bq]
            [chengis.distributed.artifact-transfer :as artifact-transfer]
            [chengis.distributed.circuit-breaker :as cb]
            [chengis.db.build-store :as build-store]
            [chengis.db.org-store :as org-store]
            [chengis.engine.events :as events]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Auth middleware (simple shared-secret)
;; ---------------------------------------------------------------------------

(defn- constant-time-equals?
  "Constant-time string comparison to prevent timing attacks.
   Uses MessageDigest/isEqual which is designed for this purpose."
  [^String a ^String b]
  (when (and a b)
    (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))

(defn- check-auth
  "Validate the auth token from request headers.
   Requires a non-blank configured token — rejects all requests if token is
   nil, empty, or whitespace-only.
   Uses constant-time comparison to prevent timing attacks."
  [req system]
  (let [expected (get-in system [:config :distributed :auth-token])
        provided (some-> (get-in req [:headers "authorization"])
                         (str/replace #"^Bearer " "")
                         str/trim)]
    (and (not (str/blank? expected))
         (boolean (constant-time-equals? expected provided)))))

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

(defn- validate-agent-registration
  "Validate and sanitize agent registration body. Returns sanitized map or nil.
   Accepts optional :org-id to assign the agent to a specific organization."
  [body]
  (when (map? body)
    (let [allowed-keys #{:name :url :labels :max-builds :system-info :org-id}
          sanitized (select-keys body allowed-keys)]
      (when (:url sanitized) ;; url is required
        (cond-> sanitized
          (:labels sanitized) (update :labels #(set (map str %)))
          (:max-builds sanitized) (update :max-builds #(min (max (int %) 1) 100))
          (:name sanitized) (update :name #(subs (str %) 0 (min (count (str %)) 64)))
          (:org-id sanitized) (update :org-id str))))))

(defn register-agent-handler
  "POST /api/agents/register — Register a new agent.
   Auth is handled by wrap-require-role :admin at the route level,
   so this handler does NOT do its own check-auth.
   Validates org-id if provided — rejects registration if org doesn't exist."
  [system]
  (fn [req]
    (let [body (parse-json-body req)]
      (if-not body
        (json-response 400 {:error "Invalid JSON body"})
        (let [validated (validate-agent-registration body)]
          (if-not validated
            (json-response 400 {:error "Invalid registration: 'url' is required, only name/url/labels/max-builds/system-info allowed"})
            ;; Validate org-id if provided
            (if-let [org-id (:org-id validated)]
              (if-let [_org (when-let [ds (:db system)]
                              (org-store/get-org ds org-id))]
                (let [agent (agent-reg/register-agent! validated)]
                  (log/info "Agent registered to org:" org-id)
                  (json-response 201 {:agent-id (:id agent)
                                      :name (:name agent)
                                      :org-id org-id
                                      :status "registered"}))
                (json-response 400 {:error (str "Organization not found: " org-id)}))
              ;; No org-id — register as shared agent
              (let [agent (agent-reg/register-agent! validated)]
                (json-response 201 {:agent-id (:id agent)
                                    :name (:name agent)
                                    :status "registered"})))))))))

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
        (if-not body
          (json-response 400 {:error "Invalid or missing JSON body"})
          (do
            ;; Feed event into the SSE event bus
            (events/publish! (assoc body :build-id build-id))
            (json-response 200 {:status "ok"})))))))

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
            ;; Decrement agent build count + update circuit breaker
            (when-let [agent-id (:agent-id body)]
              (agent-reg/decrement-builds! agent-id)
              (cb/record-success! agent-id))
            ;; Mark queue item completed (Phase 3: persistent queue)
            (when-let [ds (:db system)]
              (try
                (bq/mark-completed-by-build-id! ds build-id)
                (catch Exception e
                  (log/warn "Failed to mark queue item completed:" (.getMessage e)))))
            ;; Publish completion event
            (events/publish!
              {:event-type :build-completed
               :build-id build-id
               :data {:build-status (:build-status body)}})
            (json-response 200 {:status "ok"})))))))

(defn list-agents-handler
  "GET /api/agents — List all registered agents.
   Accepts optional ?org-id query param to filter by organization."
  [system]
  (fn [req]
    (if-not (check-auth req system)
      (json-response 401 {:error "Unauthorized"})
      (let [org-id (get-in req [:query-params "org-id"])]
        (json-response 200 {:agents (agent-reg/list-agents :org-id org-id)
                            :summary (agent-reg/registry-summary :org-id org-id)})))))
