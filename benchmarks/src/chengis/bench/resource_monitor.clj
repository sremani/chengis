(ns chengis.bench.resource-monitor
  "JVM resource monitoring for benchmark runs.
   Samples heap, GC, CPU, and thread metrics in a background thread."
  (:import [java.lang.management ManagementFactory]
           [java.lang Runtime]))

(defn sample-jvm-metrics
  "Take a single JVM resource snapshot."
  []
  (let [runtime  (Runtime/getRuntime)
        mem-bean (ManagementFactory/getMemoryMXBean)
        heap     (.getHeapMemoryUsage mem-bean)
        gc-beans (ManagementFactory/getGarbageCollectorMXBeans)
        os-bean  (ManagementFactory/getOperatingSystemMXBean)]
    {:timestamp-ms  (System/currentTimeMillis)
     :heap-used-mb  (/ (.getUsed heap) 1048576.0)
     :heap-max-mb   (/ (.getMax heap) 1048576.0)
     :heap-committed-mb (/ (.getCommitted heap) 1048576.0)
     :gc-count      (reduce + (map #(.getCollectionCount %) gc-beans))
     :gc-time-ms    (reduce + (map #(.getCollectionTime %) gc-beans))
     :thread-count  (.getThreadCount (ManagementFactory/getThreadMXBean))
     :cpu-load      (.getSystemLoadAverage os-bean)
     :available-processors (.availableProcessors runtime)}))

(defn start-monitor!
  "Start a background daemon thread that samples JVM metrics at interval-ms.
   Returns an atom containing {:running? true :samples []}."
  [interval-ms]
  (let [state (atom {:running? true :samples []})]
    (doto (Thread.
            ^Runnable
            (fn []
              (while (:running? @state)
                (try
                  (swap! state update :samples conj (sample-jvm-metrics))
                  (Thread/sleep (long interval-ms))
                  (catch InterruptedException _
                    (swap! state assoc :running? false))
                  (catch Exception _)))))
      (.setDaemon true)
      (.setName "bench-resource-monitor")
      (.start))
    state))

(defn stop-monitor!
  "Stop sampling. Returns the final samples vector."
  [monitor-state]
  (swap! monitor-state assoc :running? false)
  (Thread/sleep 100) ;; Let the monitor thread notice
  (:samples @monitor-state))

(defn resource-summary
  "Summarize resource samples into peak/avg metrics."
  [samples]
  (if (empty? samples)
    {:peak-heap-mb 0.0 :avg-heap-mb 0.0
     :avg-cpu-load 0.0 :total-gc-ms 0
     :gc-count 0 :peak-threads 0}
    (let [heaps   (map :heap-used-mb samples)
          cpus    (map :cpu-load samples)
          threads (map :thread-count samples)
          first-gc (:gc-count (first samples))
          last-gc  (:gc-count (last samples))
          first-gc-time (:gc-time-ms (first samples))
          last-gc-time  (:gc-time-ms (last samples))]
      {:peak-heap-mb    (apply max heaps)
       :avg-heap-mb     (/ (reduce + heaps) (double (count heaps)))
       :avg-cpu-load    (/ (reduce + cpus) (double (count cpus)))
       :total-gc-ms     (- last-gc-time first-gc-time)
       :gc-count        (- last-gc first-gc)
       :peak-threads    (apply max threads)
       :samples-count   (count samples)})))
