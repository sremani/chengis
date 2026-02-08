(ns chengis.engine.process
  (:require [babashka.process :as bp]
            [chengis.engine.log-masker :as masker]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent TimeUnit]))

(defn execute-command
  "Run a shell command and capture its output.
   Options:
     :command  - shell command string (required)
     :dir      - working directory (optional)
     :env      - environment variable map (optional, merged with inherited env)
     :timeout  - timeout in milliseconds (optional, default 300000 = 5 min)

   Returns:
     {:exit-code int, :stdout string, :stderr string, :duration-ms long, :timed-out? bool}"
  [{:keys [command dir env timeout mask-values]
    :or {timeout 300000}}]
  (log/info "Executing command:" command (when dir (str "(in " dir ")")))
  (let [start-time (System/currentTimeMillis)
        proc-opts (cond-> {:cmd ["sh" "-c" command]
                           :out :string
                           :err :string}
                    dir (assoc :dir dir)
                    env (assoc :extra-env env))
        proc (bp/process proc-opts)
        ;; Access the underlying java.lang.Process for timeout support
        java-proc (:proc proc)]
    (try
      (let [timed-out? (when timeout
                         (not (.waitFor java-proc timeout TimeUnit/MILLISECONDS)))
            _ (when timed-out?
                (log/warn "Command timed out after" timeout "ms:" command)
                (try (bp/destroy-tree proc)
                     (catch Exception e
                       (log/warn "destroy-tree failed, falling back:" (.getMessage e))))
                ;; Force kill if still alive after grace period
                (when-not (.waitFor java-proc 5 TimeUnit/SECONDS)
                  (log/warn "Force killing process:" command)
                  (try (.destroyForcibly java-proc)
                       (catch Exception e
                         (log/warn "destroyForcibly failed:" (.getMessage e))))))
            result (if timed-out?
                     (let [partial-stderr (try (slurp (.getErrorStream java-proc))
                                           (catch Exception _ ""))]
                       {:exit-code -1
                        :stdout ""
                        :stderr (str "Command timed out after " timeout "ms"
                                     (when (seq partial-stderr) (str "\n" partial-stderr)))
                        :timed-out? true})
                     (let [completed @proc]
                       {:exit-code (:exit completed)
                        :stdout (:out completed)
                        :stderr (:err completed)
                        :timed-out? false}))
            end-time (System/currentTimeMillis)
            final-result (cond-> (assoc result :duration-ms (- end-time start-time))
                           (seq mask-values)
                           (-> (update :stdout masker/mask-secrets mask-values)
                               (update :stderr masker/mask-secrets mask-values)))]
        (if (zero? (:exit-code final-result))
          (log/info "Command succeeded in" (:duration-ms final-result) "ms")
          (log/warn "Command failed with exit code" (:exit-code final-result)))
        final-result)
      (catch InterruptedException _
        (log/warn "Command interrupted (build cancelled):" command)
        (try (bp/destroy-tree proc)
             (catch Exception e
               (log/warn "destroy-tree failed on cancel, falling back:" (.getMessage e))))
        (when-not (.waitFor java-proc 5 TimeUnit/SECONDS)
          (try (.destroyForcibly java-proc)
               (catch Exception e
                 (log/warn "destroyForcibly failed on cancel:" (.getMessage e)))))
        {:exit-code -2
         :stdout ""
         :stderr "Build cancelled"
         :timed-out? false
         :cancelled? true
         :duration-ms (- (System/currentTimeMillis) start-time)}))))
