(ns chengis.distributed.agent-http
  "HTTP connection pooling for agent communication.
   Maintains per-agent connection state with health tracking.
   Uses http-kit with keep-alive headers for TCP reuse."
  (:require [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

;; Per-agent pool state: {agent-id → {:url :last-success :consecutive-failures :created-at}}
(defonce ^:private agent-pools (atom {}))

;; Default configuration
(def ^:private default-config
  {:max-connections-per-agent 4
   :keepalive-ms 60000
   :timeout-ms 30000
   :max-consecutive-failures 5})

;; Runtime configuration (set during startup)
(defonce ^:private pool-config (atom default-config))

(defn configure!
  "Configure the agent connection pool.
   Options: :max-connections-per-agent, :keepalive-ms, :timeout-ms, :max-consecutive-failures"
  [config]
  (reset! pool-config (merge default-config config)))

(defn- get-or-create-pool
  "Get or create a pool entry for an agent."
  [agent-id agent-url]
  (let [pools @agent-pools]
    (if-let [pool (get pools agent-id)]
      (if (= agent-url (:url pool))
        pool
        ;; URL changed, recreate
        (let [new-pool {:url agent-url
                        :last-success nil
                        :consecutive-failures 0
                        :created-at (System/currentTimeMillis)}]
          (swap! agent-pools assoc agent-id new-pool)
          new-pool))
      ;; New agent
      (let [new-pool {:url agent-url
                      :last-success nil
                      :consecutive-failures 0
                      :created-at (System/currentTimeMillis)}]
        (swap! agent-pools assoc agent-id new-pool)
        new-pool))))

(defn- record-success!
  "Record a successful request to an agent."
  [agent-id]
  (swap! agent-pools
    (fn [pools]
      (if (contains? pools agent-id)
        (update pools agent-id assoc
          :last-success (System/currentTimeMillis)
          :consecutive-failures 0)
        pools))))

(defn- record-failure!
  "Record a failed request to an agent."
  [agent-id]
  (swap! agent-pools
    (fn [pools]
      (if (contains? pools agent-id)
        (update pools agent-id update :consecutive-failures (fnil inc 0))
        pools))))

(defn healthy?
  "Check if an agent's connection pool is healthy (below failure threshold)."
  [agent-id]
  (let [pool (get @agent-pools agent-id)]
    (or (nil? pool)
        (< (or (:consecutive-failures pool) 0)
           (:max-consecutive-failures @pool-config)))))

(defn post!
  "Send a POST request to an agent with connection pooling.
   Returns a deref-able promise with the response.
   Uses keep-alive headers for TCP reuse."
  [agent-id agent-url path body headers]
  (get-or-create-pool agent-id agent-url)
  (let [config @pool-config
        url (str agent-url path)
        opts {:headers (merge {"Content-Type" "application/json"
                               "Connection" "keep-alive"
                               "Keep-Alive" (str "timeout=" (quot (:keepalive-ms config) 1000))}
                              headers)
              :body body
              :timeout (:timeout-ms config)
              :keepalive (:keepalive-ms config)}
        response-promise (promise)]
    (http/post url opts
      (fn [resp]
        (if (or (:error resp) (and (:status resp) (>= (:status resp) 500)))
          (do
            (record-failure! agent-id)
            (when (:error resp)
              (log/debug "Agent" agent-id "request failed:" (str (:error resp)))))
          (record-success! agent-id))
        (deliver response-promise resp)))
    response-promise))

(defn get!
  "Send a GET request to an agent with connection pooling."
  [agent-id agent-url path headers]
  (get-or-create-pool agent-id agent-url)
  (let [config @pool-config
        url (str agent-url path)
        opts {:headers (merge {"Connection" "keep-alive"
                               "Keep-Alive" (str "timeout=" (quot (:keepalive-ms config) 1000))}
                              headers)
              :timeout (:timeout-ms config)
              :keepalive (:keepalive-ms config)}
        response-promise (promise)]
    (http/get url opts
      (fn [resp]
        (if (or (:error resp) (and (:status resp) (>= (:status resp) 500)))
          (record-failure! agent-id)
          (record-success! agent-id))
        (deliver response-promise resp)))
    response-promise))

(defn close-pool!
  "Remove an agent's pool entry (e.g., on deregistration)."
  [agent-id]
  (swap! agent-pools dissoc agent-id)
  (log/debug "Closed pool for agent" agent-id))

(defn close-all-pools!
  "Remove all agent pool entries (e.g., on shutdown)."
  []
  (reset! agent-pools {})
  (log/info "Closed all agent connection pools"))

(defn pool-stats
  "Get pool statistics for monitoring."
  []
  (let [pools @agent-pools]
    {:total-agents (count pools)
     :healthy-agents (count (filter (fn [[id _]] (healthy? id)) pools))
     :unhealthy-agents (count (filter (fn [[id _]] (not (healthy? id))) pools))
     :agents (into {}
               (map (fn [[id pool]]
                      [id {:url (:url pool)
                           :healthy (healthy? id)
                           :consecutive-failures (:consecutive-failures pool)
                           :last-success (:last-success pool)}])
                    pools))}))
