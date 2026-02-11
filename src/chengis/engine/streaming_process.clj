(ns chengis.engine.streaming-process
  "Line-by-line process output capture with chunking.
   Provides execute-command-streaming as an alternative to
   process/execute-command for incremental log streaming."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.io BufferedReader InputStreamReader]
           [java.util.concurrent TimeUnit]))

(defn- read-stream
  "Read a stream line by line, calling on-line for each line.
   Accumulates lines into chunks and calls on-chunk when chunk is full."
  [stream source on-line on-chunk chunk-size mask-values]
  (let [reader (BufferedReader. (InputStreamReader. stream))
        buffer (StringBuilder.)
        line-count (atom 0)
        chunk-line-count (atom 0)
        chunk-index (atom 0)]
    (try
      (loop []
        (when-let [line (.readLine reader)]
          (let [masked-line (reduce (fn [l v]
                                      (if (and v (not (str/blank? v)))
                                        (str/replace l v "****")
                                        l))
                                    line (or mask-values []))]
            ;; Call on-line callback
            (when on-line
              (on-line source @line-count masked-line))
            ;; Accumulate in chunk buffer
            (when (pos? (.length buffer))
              (.append buffer "\n"))
            (.append buffer masked-line)
            (swap! line-count inc)
            (swap! chunk-line-count inc)
            ;; Flush chunk when full
            (when (>= @chunk-line-count chunk-size)
              (when on-chunk
                (on-chunk {:source source
                           :chunk-index @chunk-index
                           :line-start (- @line-count @chunk-line-count)
                           :line-count @chunk-line-count
                           :content (.toString buffer)}))
              (.setLength buffer 0)
              (swap! chunk-index inc)
              (reset! chunk-line-count 0)))
          (recur)))
      ;; Flush remaining lines
      (when (pos? @chunk-line-count)
        (when on-chunk
          (on-chunk {:source source
                     :chunk-index @chunk-index
                     :line-start (- @line-count @chunk-line-count)
                     :line-count @chunk-line-count
                     :content (.toString buffer)})))
      (catch Exception e
        (log/debug "Stream read error on" source ":" (.getMessage e))))
    @line-count))

(defn execute-command-streaming
  "Execute a shell command with line-by-line output streaming.
   Unlike process/execute-command which captures all output as a string,
   this streams output incrementally via callbacks.

   Options:
     :command       - command string to execute
     :dir           - working directory
     :env           - environment variables map
     :timeout       - timeout in ms (default 300000)
     :mask-values   - seq of strings to mask in output
     :chunk-size    - lines per chunk (default 1000)
     :on-line       - fn called with (source line-number text) per line
     :on-chunk      - fn called with chunk map per chunk

   Returns: {:exit-code int :duration-ms long :timed-out? bool
             :stdout-lines int :stderr-lines int}"
  [{:keys [command dir env timeout mask-values chunk-size on-line on-chunk]
    :or {timeout 300000 chunk-size 1000}}]
  (let [start (System/currentTimeMillis)
        pb (ProcessBuilder. (into-array String ["sh" "-c" command]))
        _ (when dir (.directory pb (io/file dir)))
        _ (when env
            (let [proc-env (.environment pb)]
              (doseq [[k v] env]
                (.put proc-env (str k) (str v)))))
        _ (.redirectErrorStream pb false)
        proc (.start pb)
        ;; Read stdout and stderr in parallel threads
        stdout-future (future (read-stream (.getInputStream proc)
                                           "stdout" on-line on-chunk
                                           chunk-size mask-values))
        stderr-future (future (read-stream (.getErrorStream proc)
                                           "stderr" on-line on-chunk
                                           chunk-size mask-values))
        timed-out? (not (.waitFor proc timeout TimeUnit/MILLISECONDS))
        _ (when timed-out? (.destroyForcibly proc))
        stdout-lines (try @stdout-future (catch Exception _ 0))
        stderr-lines (try @stderr-future (catch Exception _ 0))
        duration (- (System/currentTimeMillis) start)]
    {:exit-code (if timed-out? -1 (.exitValue proc))
     :duration-ms duration
     :timed-out? timed-out?
     :stdout-lines (or stdout-lines 0)
     :stderr-lines (or stderr-lines 0)}))
