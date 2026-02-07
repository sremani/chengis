(ns chengis.agent.worker
  "Agent build worker.
   Receives build dispatches from the master, executes them locally,
   and streams events/results back to the master."
  (:require [chengis.agent.client :as client]
            [chengis.engine.executor :as executor]
            [chengis.plugin.loader :as plugin-loader]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors ExecutorService]))

;; ---------------------------------------------------------------------------
;; Worker state
;; ---------------------------------------------------------------------------

(defonce ^:private worker-executor (atom nil))
(defonce ^:private active-builds (atom 0))

(defn- ensure-executor!
  "Ensure the worker executor is initialized. Creates a new one if needed.
   Pool size is configurable via max-builds parameter."
  [max-builds]
  (when (or (nil? @worker-executor)
            (.isShutdown ^ExecutorService @worker-executor))
    (let [pool-size (max 1 (or max-builds 2))]
      (reset! worker-executor (Executors/newFixedThreadPool pool-size))
      (log/info "Worker executor initialized with" pool-size "threads"))))

(defn current-build-count
  "Return the number of currently active builds on this agent."
  []
  @active-builds)

;; ---------------------------------------------------------------------------
;; Build execution
;; ---------------------------------------------------------------------------

(defn- make-event-fn
  "Create an event-fn that streams events to the master."
  [master-url build-id config]
  (fn [event]
    (try
      (client/send-build-event! master-url build-id event config)
      (catch Exception e
        (log/warn "Failed to stream event to master:" (.getMessage e))))))

(defn execute-dispatched-build!
  "Execute a build that was dispatched by the master.
   Runs in a thread pool, streams events back, sends final result.

   Arguments:
     agent-config - {:master-url :agent-id :auth-token :max-builds ...}
     build-payload - {:pipeline :build-id :job-id :parameters :env ...}"
  [agent-config build-payload]
  (ensure-executor! (:max-builds agent-config))
  (let [master-url (:master-url agent-config)
        agent-id (:agent-id agent-config)
        config agent-config
        build-id (:build-id build-payload)
        pipeline (:pipeline build-payload)]
    (.submit ^ExecutorService @worker-executor
      ^Callable
      (fn []
        (swap! active-builds inc)
        (try
          (log/info "Agent executing build" build-id "for" (:job-id build-payload))
          ;; Ensure plugins are loaded
          (plugin-loader/load-plugins!)
          ;; Create a minimal system for executor
          (let [system {:config (merge {:workspace {:root "agent-workspaces"}
                                        :artifacts {:root "agent-artifacts"}}
                                       (:system-config build-payload))}
                event-fn (make-event-fn master-url build-id config)
                result (executor/run-build system pipeline
                         (merge {:job-id (:job-id build-payload)
                                 :build-number (:build-number build-payload)
                                 :event-fn event-fn}
                                (when (:parameters build-payload)
                                  {:parameters (:parameters build-payload)})))]
            ;; Send result back to master
            (client/send-build-result! master-url build-id result agent-id config)
            (log/info "Build" build-id "completed:" (:build-status result))
            result)
          (catch Exception e
            (log/error "Build execution failed:" (.getMessage e))
            ;; Send failure result to master
            (client/send-build-result! master-url build-id
              {:build-status :failure
               :error (.getMessage e)}
              agent-id config)
            nil)
          (finally
            (swap! active-builds dec)))))))

;; ---------------------------------------------------------------------------
;; Worker lifecycle
;; ---------------------------------------------------------------------------

(defn shutdown-worker!
  "Shut down the worker executor. Can be re-initialized by calling execute-dispatched-build!."
  []
  (when-let [exec @worker-executor]
    (.shutdown ^ExecutorService exec)
    (reset! worker-executor nil)
    (log/info "Worker executor shut down")))
