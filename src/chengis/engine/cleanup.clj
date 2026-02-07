(ns chengis.engine.cleanup
  "System administration utilities: disk usage calculation, workspace cleanup,
   and system information gathering."
  (:require [clojure.java.io :as io]
            [chengis.engine.build-runner :as build-runner]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.lang.management ManagementFactory]
           [java.io File]
           [java.util.concurrent ThreadPoolExecutor]))

;; ---------------------------------------------------------------------------
;; Disk usage helpers
;; ---------------------------------------------------------------------------

(defn- dir-size
  "Calculate total size of a directory in bytes."
  [^File dir]
  (if (and dir (.exists dir) (.isDirectory dir))
    (reduce + 0
      (map (fn [^File f]
             (if (.isFile f)
               (.length f)
               (dir-size f)))
           (.listFiles dir)))
    0))

;; Re-export util/format-size for backward compat (admin view uses cleanup/format-size)
(def format-size util/format-size)

(defn calculate-disk-usage
  "Calculate disk usage for workspaces and artifacts.
   Returns a map of directory names to size info."
  [config]
  (let [workspace-root (get-in config [:workspace :root] "workspaces")
        artifact-root (get-in config [:artifacts :root] "artifacts")
        ws-dir (io/file workspace-root)
        art-dir (io/file artifact-root)]
    (let [ws-bytes (dir-size ws-dir)
          art-bytes (dir-size art-dir)]
      {:workspaces
       {:total-bytes ws-bytes
        :total (format-size ws-bytes)
        :per-job (when (.exists ws-dir)
                   (into {}
                     (for [^File job-dir (.listFiles ws-dir)
                           :when (.isDirectory job-dir)
                           :let [job-bytes (dir-size job-dir)]]
                       [(.getName job-dir)
                        {:bytes job-bytes
                         :formatted (format-size job-bytes)
                         :build-count (count (filter #(.isDirectory ^File %)
                                                     (.listFiles job-dir)))}])))}
       :artifacts
       {:total-bytes art-bytes
        :total (format-size art-bytes)}}))))

;; ---------------------------------------------------------------------------
;; Workspace cleanup
;; ---------------------------------------------------------------------------

(defn- delete-dir!
  "Recursively delete a directory."
  [^File dir]
  (when (.exists dir)
    (when (.isDirectory dir)
      (doseq [^File child (.listFiles dir)]
        (delete-dir! child)))
    (.delete dir)))

(defn cleanup-workspaces!
  "Clean up old workspace directories, keeping the most recent N per job.
   Returns a summary of what was cleaned."
  [config & {:keys [retention-builds] :or {retention-builds 10}}]
  (let [workspace-root (get-in config [:workspace :root] "workspaces")
        ws-dir (io/file workspace-root)
        retention (or (get-in config [:cleanup :retention-builds]) retention-builds)]
    (if-not (.exists ws-dir)
      {:cleaned 0 :freed-bytes 0}
      (let [cleaned (atom 0)
            freed (atom 0)]
        (doseq [^File job-dir (.listFiles ws-dir)
                :when (.isDirectory job-dir)]
          (let [build-dirs (->> (.listFiles job-dir)
                                (filter #(.isDirectory ^File %))
                                (sort-by #(.lastModified ^File %) >))]
            ;; Keep the most recent `retention` builds, delete the rest
            (doseq [^File old-dir (drop retention build-dirs)]
              (let [size (dir-size old-dir)]
                (log/info "Cleaning workspace:" (.getPath old-dir)
                          "(" (format-size size) ")")
                (try
                  (delete-dir! old-dir)
                  (swap! cleaned inc)
                  (swap! freed + size)
                  (catch Exception e
                    (log/warn "Failed to clean" (.getPath old-dir) (.getMessage e))))))))
        {:cleaned @cleaned
         :freed-bytes @freed
         :freed (format-size @freed)}))))

;; ---------------------------------------------------------------------------
;; System information
;; ---------------------------------------------------------------------------

(defn get-system-info
  "Gather JVM and system information for the admin dashboard."
  []
  (let [runtime (Runtime/getRuntime)
        mem-bean (ManagementFactory/getMemoryMXBean)
        heap (.getHeapMemoryUsage mem-bean)
        runtime-bean (ManagementFactory/getRuntimeMXBean)
        os-bean (ManagementFactory/getOperatingSystemMXBean)
        ^ThreadPoolExecutor executor build-runner/build-executor]
    {:jvm
     {:max-memory (format-size (.maxMemory runtime))
      :total-memory (format-size (.totalMemory runtime))
      :free-memory (format-size (.freeMemory runtime))
      :used-memory (format-size (- (.totalMemory runtime) (.freeMemory runtime)))
      :heap-used (format-size (.getUsed heap))
      :heap-max (format-size (.getMax heap))
      :heap-pct (when (pos? (.getMax heap))
                  (int (* 100 (/ (double (.getUsed heap)) (.getMax heap)))))}
     :uptime
     {:millis (.getUptime runtime-bean)
      :formatted (let [ms (.getUptime runtime-bean)
                       secs (/ ms 1000)
                       mins (/ secs 60)
                       hours (/ mins 60)
                       days (int (/ hours 24))]
                   (cond
                     (>= days 1) (format "%dd %dh %dm" days (int (mod hours 24)) (int (mod mins 60)))
                     (>= hours 1) (format "%dh %dm" (int hours) (int (mod mins 60)))
                     :else (format "%dm %ds" (int mins) (int (mod secs 60)))))}
     :os
     {:name (.getName os-bean)
      :arch (.getArch os-bean)
      :processors (.getAvailableProcessors os-bean)
      :load-average (.getSystemLoadAverage os-bean)}
     :build-pool
     {:active (.getActiveCount executor)
      :pool-size (.getPoolSize executor)
      :max-pool-size (.getMaximumPoolSize executor)
      :completed (.getCompletedTaskCount executor)
      :queued (.size (.getQueue executor))}
     :active-builds
     {:count (count (build-runner/get-active-build-ids))
      :ids (vec (build-runner/get-active-build-ids))}}))

(defn get-db-size
  "Get the SQLite database file size."
  [config]
  (let [db-path (get-in config [:database :path] "chengis.db")
        f (io/file db-path)]
    (when (.exists f)
      {:bytes (.length f)
       :formatted (format-size (.length f))})))
