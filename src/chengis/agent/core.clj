(ns chengis.agent.core
  "Agent entry point.
   Starts an HTTP server that receives build dispatches from the master,
   registers with the master, and sends periodic heartbeats.

   Usage: lein run agent [--master-url URL] [--port PORT] [--labels LABELS]"
  (:require [chengis.agent.client :as client]
            [chengis.agent.heartbeat :as heartbeat]
            [chengis.agent.worker :as worker]
            [chengis.plugin.loader :as plugin-loader]
            [clojure.data.json :as json]
            [org.httpkit.server :as http-server]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Agent HTTP handlers
;; ---------------------------------------------------------------------------

(defn- builds-handler
  "POST /builds — Receive a build dispatch from the master."
  [agent-config req]
  (let [body (try
               (json/read-str (slurp (:body req)) :key-fn keyword)
               (catch Exception _ nil))]
    (if-not body
      {:status 400 :body (json/write-str {:error "Invalid JSON"})}
      (do
        (worker/execute-dispatched-build! agent-config body)
        {:status 202
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:status "accepted"
                                :build-id (:build-id body)})}))))

(defn- health-handler
  "GET /health — Agent health check."
  [_req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:status "healthy"
                          :active-builds (worker/current-build-count)})})

(defn- agent-router
  "Simple routing for the agent HTTP server."
  [agent-config req]
  (case [(:request-method req) (:uri req)]
    [:post "/builds"]  (builds-handler agent-config req)
    [:get "/health"]   (health-handler req)
    {:status 404 :body "Not found"}))

;; ---------------------------------------------------------------------------
;; Agent lifecycle
;; ---------------------------------------------------------------------------

(defn start-agent!
  "Start the agent: register with master, start heartbeat, start HTTP server.

   Config map:
     :master-url  - URL of the Chengis master (e.g., http://localhost:8080)
     :port        - Agent HTTP server port (default 9090)
     :labels      - Set of labels (e.g., #{\"linux\" \"docker\"})
     :max-builds  - Max concurrent builds (default 2)
     :auth-token  - Shared secret for master auth
     :name        - Agent display name"
  [{:keys [master-url port labels max-builds auth-token name] :as config}]
  (let [port (or port 9090)
        agent-url (str "http://localhost:" port)]
    (log/info "Starting Chengis agent on port" port)

    ;; Load plugins
    (plugin-loader/load-plugins!)

    ;; Register with master
    (let [agent-id (client/register-with-master!
                     master-url
                     {:name (or name (str "agent-" (.. java.net.InetAddress getLocalHost getHostName)))
                      :url agent-url
                      :labels (vec (or labels #{}))
                      :max-builds (or max-builds 2)
                      :system-info {:os (System/getProperty "os.name")
                                    :arch (System/getProperty "os.arch")
                                    :jvm (System/getProperty "java.version")}}
                     config)]
      (when-not agent-id
        (throw (ex-info "Failed to register with master"
                        {:master-url master-url})))

      (let [agent-config (assoc config :agent-id agent-id)]
        ;; Start heartbeat with system resource reporting
        (heartbeat/start-heartbeat!
          {:master-url master-url
           :agent-id agent-id
           :interval-ms 30000
           :config config
           :status-fn (fn []
                        (let [runtime (Runtime/getRuntime)
                              os-bean (java.lang.management.ManagementFactory/getOperatingSystemMXBean)]
                          {:current-builds (worker/current-build-count)
                           :system-info
                           {:heap-used-mb (int (/ (- (.totalMemory runtime) (.freeMemory runtime))
                                                  1048576))
                            :heap-max-mb (int (/ (.maxMemory runtime) 1048576))
                            :cpu-load (.getSystemLoadAverage os-bean)
                            :available-processors (.availableProcessors runtime)
                            :disk-free-mb (int (/ (.getFreeSpace (java.io.File. ".")) 1048576))}}))})

        ;; Start HTTP server
        (let [server (http-server/run-server
                       (partial agent-router agent-config)
                       {:port port})]
          (log/info "Agent started — registered as" agent-id)
          (log/info "Master:" master-url)
          (log/info "Labels:" labels)

          ;; Return stop function
          {:stop-fn (fn []
                      (heartbeat/stop-heartbeat!)
                      (worker/shutdown-worker!)
                      (server)
                      (log/info "Agent stopped"))
           :agent-id agent-id
           :port port})))))
