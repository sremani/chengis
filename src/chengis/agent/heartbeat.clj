(ns chengis.agent.heartbeat
  "Periodic heartbeat from agent to master.
   Uses chime for scheduling."
  (:require [chengis.agent.client :as client]
            [chime.core :as chime]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; Heartbeat scheduler
;; ---------------------------------------------------------------------------

(defonce ^:private heartbeat-scheduler (atom nil))

(defn- heartbeat-times
  "Generate an infinite sequence of instants every `interval-ms` milliseconds."
  [interval-ms]
  (chime/periodic-seq (Instant/now) (Duration/ofMillis interval-ms)))

(defn start-heartbeat!
  "Start the periodic heartbeat to the master.
   Returns a function to stop the heartbeat."
  [{:keys [master-url agent-id interval-ms config status-fn]}]
  (let [interval (or interval-ms 30000)
        sched (chime/chime-at
                (heartbeat-times interval)
                (fn [_time]
                  (let [status-info (when status-fn (status-fn))]
                    (when-not (client/send-heartbeat! master-url agent-id
                                                      (or status-info {}) config)
                      (log/warn "Heartbeat failed — master may be unreachable")))))]
    (reset! heartbeat-scheduler sched)
    (log/info "Heartbeat started: every" interval "ms")
    sched))

(defn stop-heartbeat!
  "Stop the heartbeat scheduler."
  []
  (when-let [sched @heartbeat-scheduler]
    (.close sched)
    (reset! heartbeat-scheduler nil)
    (log/info "Heartbeat stopped")))
