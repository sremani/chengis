(ns chengis.engine.executor
  "Pipeline execution engine. Runs stages sequentially or in parallel (DAG mode),
   steps within a stage either sequentially or in parallel, handles failures
   and abort signals."
  (:require [clojure.core.async :refer [<!! thread]]
            [clojure.string :as str]
            [chengis.db.artifact-store :as artifact-store]
            [chengis.db.secret-store :as secret-store]
            [chengis.dsl.chengisfile :as chengisfile]
            [chengis.dsl.yaml :as yaml-parser]
            [chengis.engine.approval :as approval]
            [chengis.engine.cache :as cache]
            [chengis.engine.dag :as dag]
            [chengis.engine.log-context :as log-ctx]
            [chengis.engine.tracing :as tracing]
            [chengis.engine.policy :as policy]
            [chengis.engine.stage-cache :as stage-cache]
            [chengis.engine.matrix :as matrix]
            [chengis.engine.artifacts :as artifacts]
            [chengis.engine.notify :as notify]
            [chengis.engine.provenance :as provenance]
            [chengis.engine.sbom :as sbom]
            [chengis.engine.vulnerability-scanner :as vuln-scanner]
            [chengis.engine.license-scanner :as license-scanner]
            [chengis.engine.signing :as signing]
            [chengis.engine.git :as git]
            [chengis.engine.process :as process]
            [chengis.engine.workspace :as workspace]
            [chengis.feature-flags :as ff]
            [chengis.metrics :as metrics]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as plugin-reg]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant]
           [java.util.concurrent Semaphore]))

(defn- now []
  (str (Instant/now)))

(defn- emit
  "Emit a build event if event-fn is present in the build context."
  [build-ctx event-type data]
  (when-let [f (:event-fn build-ctx)]
    (f {:build-id (:build-id build-ctx)
        :event-type event-type
        :timestamp (now)
        :data data})))

(defn- cancelled?
  "Check if the build has been cancelled."
  [build-ctx]
  (when-let [flag (:cancelled? build-ctx)]
    @flag))

(defn- evaluate-condition
  "Check whether a step/stage condition is met given the build context."
  [condition build-ctx]
  (if (nil? condition)
    true
    (case (:type condition)
      :branch (= (:value condition) (get-in build-ctx [:parameters :branch] "main"))
      :param  (= (:value condition) (get-in build-ctx [:parameters (keyword (:param condition))]))
      :always true
      true)))

(defn run-step
  "Execute a single step. Returns a step result map."
  [build-ctx step-def]
  (let [step-name (:step-name step-def)
        stage-name (:current-stage build-ctx)
        started-at (now)]
    (log-ctx/with-step-context step-name
    ;; Check cancellation before running
    (if (cancelled? build-ctx)
      (do
        (log/info "Step aborted (build cancelled):" step-name)
        (emit build-ctx :step-completed
              {:stage-name stage-name :step-name step-name :step-status :aborted})
        {:step-name step-name
         :step-status :aborted
         :exit-code -2
         :stdout ""
         :stderr "Build cancelled"
         :started-at started-at
         :completed-at (now)})
      (do
        (log/info "Running step:" step-name)
        (emit build-ctx :step-started {:stage-name stage-name :step-name step-name})
        (if-not (evaluate-condition (:condition step-def) build-ctx)
          (let [result {:step-name step-name
                        :step-status :skipped
                        :exit-code 0
                        :started-at started-at
                        :completed-at (now)}]
            (log/info "Skipping step (condition not met):" step-name)
            (emit build-ctx :step-completed
                  (merge {:stage-name stage-name} result))
            result)
          (let [step-type (or (:type step-def) :shell)
                executor (plugin-reg/get-step-executor step-type)
                result (if executor
                         (proto/execute-step executor build-ctx step-def)
                         ;; Fallback to direct shell execution for backward compat
                         (process/execute-command
                           (cond-> {:command (:command step-def)
                                    :dir (:workspace build-ctx)
                                    :env (merge (:env build-ctx) (:env step-def))
                                    :timeout (:timeout step-def)}
                             (seq (:mask-values build-ctx))
                             (assoc :mask-values (:mask-values build-ctx)))))
                status (cond
                         (:cancelled? result) :aborted
                         (zero? (:exit-code result)) :success
                         :else :failure)]
            (when (= status :failure)
              (log/error "Step failed:" step-name "exit code:" (:exit-code result))
              (when (seq (:stderr result))
                (log/error "stderr:" (:stderr result))))
            (let [step-result {:step-name step-name
                               :step-status status
                               :exit-code (:exit-code result)
                               :stdout (:stdout result)
                               :stderr (:stderr result)
                               :duration-ms (:duration-ms result)
                               :started-at started-at
                               :completed-at (now)}]
              (emit build-ctx :step-completed
                    (merge {:stage-name stage-name} step-result))
              ;; Record step duration metric (try/catch to never break builds)
              (when-let [ms (:duration-ms result)]
                (try
                  (metrics/record-step-duration!
                    (:metrics-registry build-ctx) step-name status (/ (double ms) 1000.0))
                  (catch Exception e
                    (log/debug "Failed to record step metric:" (.getMessage e)))))
              step-result))))))))

(defn- run-steps-sequential
  "Run steps one by one. Stops on first failure or cancellation."
  [build-ctx steps]
  (reduce (fn [results step-def]
            (if (cancelled? build-ctx)
              (reduced results)
              (let [result (run-step build-ctx step-def)]
                (let [updated (conj results result)]
                  (if (#{:failure :aborted} (:step-status result))
                    (reduced updated)
                    updated)))))
          []
          steps))

(def ^:private ^:const default-max-parallel-steps
  "Default maximum number of steps that can run concurrently within a stage.
   Prevents thread pool exhaustion from large parallel step counts (e.g. matrix builds)."
  8)

(defn- run-steps-parallel
  "Run steps concurrently using core.async threads. Waits for all to complete.
   Uses a semaphore to limit concurrent execution and prevent thread pool exhaustion."
  [build-ctx steps]
  (let [max-concurrent (or (:max-parallel-steps build-ctx) default-max-parallel-steps)
        sem (Semaphore. (int max-concurrent))
        result-chans (mapv (fn [step-def]
                             (thread
                               (.acquire sem)
                               (try
                                 (run-step build-ctx step-def)
                                 (finally
                                   (.release sem)))))
                           steps)
        results (mapv <!! result-chans)]
    results))

(defn- containerize-steps
  "If a stage has a :container config, wrap all :shell steps to run as :docker.
   Steps that already have :type :docker are left unchanged."
  [steps container-config]
  (if-not container-config
    steps
    (mapv (fn [step-def]
            (if (= :shell (or (:type step-def) :shell))
              (merge step-def
                     {:type :docker
                      :image (:image container-config)}
                     (when (:volumes container-config)
                       {:volumes (:volumes container-config)})
                     (when (:workdir container-config)
                       {:workdir (:workdir container-config)})
                     (when (:network container-config)
                       {:network (:network container-config)})
                     (when (:pull-policy container-config)
                       {:pull-policy (:pull-policy container-config)})
                     (when (:docker-args container-config)
                       {:docker-args (:docker-args container-config)})
                     (when (:cache-volumes container-config)
                       {:cache-volumes (:cache-volumes container-config)}))
              step-def))
          steps)))

(defn run-stage
  "Execute a pipeline stage. Returns a stage result map.
   If the stage has a :container config, shell steps are wrapped to run in Docker."
  [build-ctx stage-def]
  (let [stage-name (:stage-name stage-def)
        started-at (now)
        start-ns (System/nanoTime)
        ;; Add current stage name to context so steps can reference it
        build-ctx (assoc build-ctx :current-stage stage-name)]
    (log-ctx/with-stage-context stage-name
    ;; Check cancellation before running stage
    (if (cancelled? build-ctx)
      (do
        (log/info "Stage aborted (build cancelled):" stage-name)
        (emit build-ctx :stage-completed {:stage-name stage-name :stage-status :aborted})
        {:stage-name stage-name
         :stage-status :aborted
         :step-results []
         :started-at started-at
         :completed-at (now)})
      (do
        (log/info "=== Stage:" stage-name "===")
        (emit build-ctx :stage-started {:stage-name stage-name})
        (if-not (evaluate-condition (:condition stage-def) build-ctx)
          (let [result {:stage-name stage-name
                        :stage-status :skipped
                        :step-results []
                        :started-at started-at
                        :completed-at (now)}]
            (log/info "Skipping stage (condition not met):" stage-name)
            (emit build-ctx :stage-completed {:stage-name stage-name :stage-status :skipped})
            result)
          (let [;; Apply container wrapping if stage has :container config
                effective-steps (containerize-steps (:steps stage-def)
                                                    (:container stage-def))
                step-results (if (:parallel? stage-def)
                               (run-steps-parallel build-ctx effective-steps)
                               (run-steps-sequential build-ctx effective-steps))
                any-failed? (some #(= :failure (:step-status %)) step-results)
                any-aborted? (some #(= :aborted (:step-status %)) step-results)
                all-skipped? (every? #(= :skipped (:step-status %)) step-results)
                stage-status (cond
                               any-aborted? :aborted
                               any-failed?  :failure
                               all-skipped? :skipped
                               :else        :success)]
            (log/info "Stage" stage-name "completed with status:" stage-status)
            (emit build-ctx :stage-completed {:stage-name stage-name :stage-status stage-status})
            ;; Record stage duration metric (try/catch to never break builds)
            (let [duration-s (/ (double (- (System/nanoTime) start-ns)) 1e9)]
              (try
                (metrics/record-stage-duration!
                  (:metrics-registry build-ctx) stage-name stage-status duration-s)
                (catch Exception e
                  (log/debug "Failed to record stage metric:" (.getMessage e)))))
            {:stage-name stage-name
             :stage-status stage-status
             :step-results step-results
             :started-at started-at
             :completed-at (now)})))))))

(defn- run-post-action-group
  "Run a group of post-action steps as an implicit stage.
   Post-action failures are logged but do NOT affect build status."
  [build-ctx stage-name steps]
  (when (seq steps)
    (let [stage-def {:stage-name stage-name
                     :parallel? false
                     :steps steps}]
      (log/info "--- Post-action:" stage-name "---")
      (run-stage build-ctx stage-def))))

(defn- run-post-actions
  "Execute post-build action groups based on build status.
   Runs :always regardless, :on-success for successful builds,
   :on-failure for failed/aborted builds.
   Returns a vector of stage results for all executed post-action groups."
  [build-ctx build-status post-actions]
  (when (seq post-actions)
    (log/info "--- Running post-build actions ---")
    (let [results (atom [])]
      ;; Always runs regardless of build status
      (when-let [always-steps (:always post-actions)]
        (when-let [result (run-post-action-group build-ctx "post:always" always-steps)]
          (swap! results conj result)))
      ;; On success
      (when (and (= :success build-status) (:on-success post-actions))
        (when-let [result (run-post-action-group build-ctx "post:on-success" (:on-success post-actions))]
          (swap! results conj result)))
      ;; On failure (failure or aborted)
      (when (and (#{:failure :aborted} build-status) (:on-failure post-actions))
        (when-let [result (run-post-action-group build-ctx "post:on-failure" (:on-failure post-actions))]
          (swap! results conj result)))
      @results)))

;; ---------------------------------------------------------------------------
;; Stage execution with policy + approval checks
;; ---------------------------------------------------------------------------

(defn- execute-stage-with-checks
  "Run a single stage through the cache → policy → approval → execution pipeline.
   Includes build result caching (skip if cached) and artifact cache restore/save.
   Returns a stage result map. Used by both sequential and DAG execution."
  [system build-ctx stage-def]
  ;; 0. Build result cache check (skip stage if inputs match a previous success)
  (let [cache-check (stage-cache/should-skip-stage? system build-ctx stage-def)]
    (if (:skip? cache-check)
      (do
        (log/info "Stage" (:stage-name stage-def) "skipped (cached result)")
        (emit build-ctx :stage-cached {:stage-name (:stage-name stage-def)})
        (:cached-result cache-check))
      ;; 1. Policy check (can deny outright or override approval requirements)
      (let [policy-result (policy/check-stage-policies! system build-ctx stage-def)]
        (if-not (:proceed policy-result)
          ;; Policy denied — abort
          (do
            (log/warn "Stage" (:stage-name stage-def)
                      "blocked by policy:" (:reason policy-result))
            (emit build-ctx :stage-policy-denied
                  {:stage-name (:stage-name stage-def)
                   :reason (:reason policy-result)})
            {:stage-name (:stage-name stage-def)
             :stage-status :aborted
             :step-results []
             :reason (:reason policy-result)})
          ;; 2. Apply approval overrides from policy, then check approval
          (let [effective-stage (if-let [overrides (:approval-overrides policy-result)]
                                  (policy/apply-approval-overrides stage-def overrides)
                                  stage-def)
                approval-result (approval/check-stage-approval!
                                  system build-ctx effective-stage)]
            (if-not (:proceed approval-result)
              ;; Approval denied/timed-out — abort
              (do
                (log/warn "Stage" (:stage-name stage-def)
                          "approval denied:" (:reason approval-result))
                (emit build-ctx :stage-skipped
                      {:stage-name (:stage-name stage-def)
                       :reason (:reason approval-result)})
                {:stage-name (:stage-name stage-def)
                 :stage-status :aborted
                 :step-results []
                 :reason (:reason approval-result)})
              ;; 3. Cache restore (before stage execution)
              (let [_ (when-let [cache-decls (:cache stage-def)]
                        (try
                          (cache/restore-cache!
                            (:workspace build-ctx) (:config system) (:db system)
                            (:job-id build-ctx) cache-decls)
                          (catch Exception e
                            (log/debug "Cache restore failed:" (.getMessage e)))))
                    ;; 4. Run stage
                    result (run-stage build-ctx stage-def)]
                ;; 5. Cache save (after successful stage)
                (when (and (= :success (:stage-status result))
                           (seq (:cache stage-def)))
                  (try
                    (cache/save-cache!
                      (:workspace build-ctx) (:config system) (:db system)
                      (:job-id build-ctx) (:cache stage-def))
                    (catch Exception e
                      (log/debug "Cache save failed:" (.getMessage e)))))
                ;; 6. Save stage result to cache for future builds
                (when (and (= :success (:stage-status result))
                           (:fingerprint cache-check))
                  (try
                    (stage-cache/save-stage-result!
                      (:db system)
                      {:job-id (:job-id build-ctx)
                       :fingerprint (:fingerprint cache-check)
                       :stage-name (:stage-name stage-def)
                       :stage-result result
                       :git-commit (get-in build-ctx [:env "GIT_COMMIT"])
                       :org-id (:org-id build-ctx)})
                    (catch Exception e
                      (log/debug "Stage cache save failed:" (.getMessage e)))))
                result))))))))


;; ---------------------------------------------------------------------------
;; Sequential stage execution (original behavior)
;; ---------------------------------------------------------------------------

(defn- run-stages-sequential
  "Run stages in sequential order. Stops on first failure or cancellation.
   This is the original execution mode, used when no :depends-on is present."
  [system build-ctx stages]
  (reduce (fn [results stage-def]
            (if (cancelled? build-ctx)
              (reduced results)
              (let [result (execute-stage-with-checks system build-ctx stage-def)
                    updated (conj results result)]
                (if (#{:failure :aborted} (:stage-status result))
                  (do
                    (log/error "Pipeline stopped: stage"
                               (:stage-name stage-def)
                               (name (:stage-status result)))
                    (reduced updated))
                  updated))))
          []
          stages))

;; ---------------------------------------------------------------------------
;; DAG-based parallel stage execution
;; ---------------------------------------------------------------------------

(defn- run-stages-dag
  "Run stages in parallel according to their :depends-on DAG.
   Stages with no dependencies (or empty deps) start immediately.
   Stages wait until all their dependencies have completed successfully.
   On failure/abort, downstream dependents are skipped.

   Uses a semaphore to limit concurrent stage execution."
  [system build-ctx stages]
  (let [dag-map (dag/build-dag stages)
        max-concurrent (get-in system [:config :parallel-stages :max-concurrent] 4)
        ^java.util.concurrent.Semaphore semaphore (Semaphore. (int max-concurrent))
        ;; Track results and state
        completed (atom #{})       ;; set of completed stage names
        failed (atom #{})          ;; set of failed/aborted stage names
        results (atom [])          ;; ordered results vector
        stage-map (into {} (map (fn [s] [(:stage-name s) s]) stages))]
    (log/info "DAG execution mode: max-concurrent=" max-concurrent
              "stages=" (count stages))
    (loop []
      (when-not (cancelled? build-ctx)
        (let [ready (dag/ready-stages dag-map @completed)
              ;; Remove stages that are already done or currently being processed
              all-done (into @completed @failed)
              remaining (remove #(contains? all-done %) (keys dag-map))
              runnable (remove #(contains? all-done %) ready)]
          (if (and (empty? runnable) (seq remaining))
            ;; Stages remain but none are runnable — check if they have failed deps
            (let [blocked (filter (fn [stage-name]
                                    (let [deps (get dag-map stage-name)]
                                      (some #(contains? @failed %) deps)))
                                  remaining)]
              (if (seq blocked)
                ;; Mark blocked stages as skipped due to failed dependencies
                (do
                  (doseq [stage-name blocked]
                    (let [result {:stage-name stage-name
                                  :stage-status :aborted
                                  :step-results []
                                  :reason "Dependency failed"}]
                      (log/warn "Stage" stage-name "skipped: dependency failed")
                      (emit build-ctx :stage-skipped
                            {:stage-name stage-name :reason "Dependency failed"})
                      (swap! results conj result)
                      (swap! failed conj stage-name)))
                  (recur))
                ;; Stages still waiting for deps — wait and retry
                (do
                  (Thread/sleep 50)
                  (recur))))
            (when (seq runnable)
              ;; Launch ready stages in parallel (bounded by semaphore).
              ;; Acquire semaphore BEFORE spawning thread to avoid consuming
              ;; core.async thread pool slots for stages that would just block.
              (let [futures (doall
                              (map (fn [stage-name]
                                     (let [stage-def (get stage-map stage-name)]
                                       (.acquire semaphore)
                                       (thread
                                         (try
                                           (let [result (execute-stage-with-checks
                                                          system build-ctx stage-def)]
                                             {:stage-name stage-name
                                              :result result})
                                           (finally
                                             (.release semaphore))))))
                                   runnable))
                    ;; Wait for all launched stages to complete
                    stage-results (mapv <!! futures)]
                ;; Process results
                (doseq [{:keys [stage-name result]} stage-results]
                  (swap! results conj result)
                  (if (#{:failure :aborted} (:stage-status result))
                    (swap! failed conj stage-name)
                    (swap! completed conj stage-name)))
                ;; Continue if there are more stages to run
                (when (< (+ (count @completed) (count @failed)) (count dag-map))
                  (recur))))))))
    @results))

;; ---------------------------------------------------------------------------
;; Build entry point
;; ---------------------------------------------------------------------------

(defn run-build
  "Execute a complete build for a pipeline definition.

   Arguments:
     system      - system map containing :config and optionally :db
     pipeline    - pipeline definition map (from DSL)
     params      - build parameters map (supports optional :event-fn for live updates
                   and :cancelled? atom for cancellation)

   Returns a build result map with status, stage results, and timing info."
  [system pipeline params]
  (let [build-id (util/generate-id)
        job-id (or (:job-id params) (:pipeline-name pipeline))
        build-number (or (:build-number params) 1)
        org-id (:org-id params)
        workspace-root (get-in system [:config :workspace :root] "workspaces")
        ws (workspace/create-workspace workspace-root job-id build-number)
        started-at (now)
        ;; Minimal build-ctx for emitting early events
        early-ctx {:build-id build-id
                   :event-fn (:event-fn params)
                   :cancelled? (:cancelled? params)}]
    (log-ctx/with-build-context build-id job-id (or org-id "default-org")
    (log/info "========================================")
    (log/info "Starting build" build-id "for" (:pipeline-name pipeline))
    (log/info "Workspace:" ws)
    (log/info "========================================")
    ;; --- Git checkout phase (before stages) ---
    (let [source (:source pipeline)
          git-result (when (and source (= :git (:type source)))
                       (emit early-ctx :git-started {:url (git/sanitize-url (:url source))})
                       (let [commit-override (get-in params [:parameters :commit])
                             branch-override (get-in params [:parameters :branch])
                             effective-source (cond-> source
                                                branch-override (assoc :branch branch-override))
                             result (git/checkout-source! effective-source ws commit-override)]
                         (if (:success? result)
                           (do
                             (log/info "Git checkout complete:" (get-in result [:git-info :commit-short]))
                             (emit early-ctx :git-completed {:git-info (:git-info result)})
                             result)
                           (do
                             (log/error "Git checkout failed:" (:error result))
                             (emit early-ctx :git-failed {:error (:error result)})
                             result))))
          git-info (:git-info git-result)]
      ;; Fail fast if git checkout failed
      (if (and git-result (not (:success? git-result)))
        (do
          (emit early-ctx :build-completed {:build-status :failure})
          {:build-id build-id
           :job-id job-id
           :build-number build-number
           :build-status :failure
           :stage-results []
           :workspace ws
           :started-at started-at
           :completed-at (now)
           :git-info nil
           :pipeline-source "server"})
        ;; --- Pipeline-as-Code detection (multi-format) ---
        ;; Priority: Chengisfile (EDN) → YAML workflow → server pipeline
        (let [;; 1. Try Chengisfile (EDN)
              cf-result (when (and git-result (:success? git-result)
                                   (chengisfile/chengisfile-exists? ws))
                          (log/info "Chengisfile detected in workspace")
                          (emit early-ctx :chengisfile-detected {:workspace ws})
                          (chengisfile/parse-chengisfile
                            (chengisfile/chengisfile-path ws)))
              ;; 2. Try YAML workflow (if no Chengisfile)
              yaml-result (when (and git-result (:success? git-result)
                                     (not (and cf-result (:pipeline cf-result))))
                            (when-let [yaml-path (yaml-parser/detect-yaml-file ws)]
                              (log/info "YAML workflow detected:" yaml-path)
                              (emit early-ctx :yaml-detected {:path yaml-path})
                              (yaml-parser/parse-yaml-workflow yaml-path)))
              ;; Build effective pipeline from first successful parse
              [pac-result pac-source]
              (cond
                (and cf-result (:pipeline cf-result))
                [cf-result "chengisfile"]

                (and yaml-result (:pipeline yaml-result))
                [yaml-result "yaml"]

                :else [nil "server"])
              effective-pipeline
              (if pac-result
                (let [pac-pipeline (:pipeline pac-result)]
                  (log/info "Using" pac-source "pipeline:"
                            (count (:stages pac-pipeline)) "stages")
                  (cond-> (assoc pipeline :stages (:stages pac-pipeline))
                    (:description pac-pipeline)
                    (assoc :description (:description pac-pipeline))
                    (:container pac-pipeline)
                    (assoc :container (:container pac-pipeline))
                    (:post-actions pac-pipeline)
                    (assoc :post-actions (:post-actions pac-pipeline))
                    (:artifacts pac-pipeline)
                    (assoc :artifacts (:artifacts pac-pipeline))
                    (:notify pac-pipeline)
                    (assoc :notify (:notify pac-pipeline))))
                (do
                  (when (and cf-result (:error cf-result))
                    (log/warn "Chengisfile parse error:" (:error cf-result))
                    (emit early-ctx :chengisfile-error {:error (:error cf-result)}))
                  (when (and yaml-result (:error yaml-result))
                    (log/warn "YAML parse error:" (:error yaml-result))
                    (emit early-ctx :yaml-error {:error (:error yaml-result)}))
                  pipeline))
              pipeline-source pac-source]
          ;; --- Normal build execution ---
          (let [git-env (when git-info
                          {"GIT_BRANCH"       (:branch git-info)
                           "GIT_COMMIT"       (:commit git-info)
                           "GIT_COMMIT_SHORT" (:commit-short git-info)
                           "GIT_AUTHOR"       (:author git-info)
                           "GIT_MESSAGE"      (:message git-info)})
                ;; --- Secrets injection ---
                secrets-map (when (:db system)
                              (try
                                (secret-store/get-secrets-for-build
                                  (:db system) (:config system) job-id :org-id org-id)
                                (catch Exception e
                                  (log/warn "Failed to load secrets:" (.getMessage e))
                                  {})))
                secret-values (when (seq secrets-map) (set (vals secrets-map)))
                ;; --- Parameter env vars ---
                param-env (when-let [p (:parameters params)]
                            (reduce-kv (fn [m k v]
                                         (assoc m
                                           (str "PARAM_" (str/upper-case
                                                           (str/replace (name k) "-" "_")))
                                           (str v)))
                                       {} p))
                build-env (merge {"BUILD_ID" build-id
                                  "BUILD_NUMBER" (str build-number)
                                  "JOB_NAME" job-id
                                  "WORKSPACE" ws}
                                 git-env
                                 secrets-map
                                 param-env
                                 (:env params))
                build-ctx {:build-id build-id
                           :job-id job-id
                           :build-number build-number
                           :workspace ws
                           :parameters (merge (or (:parameters params) {})
                                              (when git-info
                                                {:branch (:branch git-info)}))
                           :env build-env
                           :event-fn (:event-fn params)
                           :cancelled? (:cancelled? params)
                           :mask-values secret-values
                           :docker-config (get-in system [:config :docker])
                           :max-parallel-steps (or (get-in system [:config :thread-pools :max-parallel-steps])
                                                  (get-in system [:config :parallel-steps :max-concurrent])
                                                  default-max-parallel-steps)
                           :metrics-registry (:metrics system)
                           :db (:db system)
                           :org-id org-id}
                ;; Propagate pipeline-level :container to stages that don't have their own
                pipeline-container (:container effective-pipeline)
                pre-matrix-stages (if pipeline-container
                                    (mapv (fn [s]
                                            (if (:container s)
                                              s
                                              (assoc s :container pipeline-container)))
                                          (:stages effective-pipeline))
                                    (:stages effective-pipeline))
                ;; Matrix expansion: if the pipeline has a :matrix config,
                ;; expand each stage into N copies (one per combination)
                matrix-config (:matrix effective-pipeline)
                max-combos (get-in system [:config :matrix :max-combinations]
                                   matrix/default-max-combinations)
                effective-stages (if matrix-config
                                   (matrix/expand-stages pre-matrix-stages matrix-config
                                                         :max max-combos)
                                   pre-matrix-stages)]
            (emit build-ctx :build-started {:job-id job-id :build-number build-number})
            (let [;; Choose execution mode: DAG (parallel) or sequential
                  use-dag? (and (dag/has-dag? effective-stages)
                                (ff/enabled? (:config system) :parallel-stage-execution))
                  stage-results
                  (if use-dag?
                    (do (log/info "Using DAG execution mode")
                        (run-stages-dag system build-ctx effective-stages))
                    (run-stages-sequential system build-ctx effective-stages))
                  any-failed? (some #(= :failure (:stage-status %)) stage-results)
                  any-aborted? (some #(= :aborted (:stage-status %)) stage-results)
                  build-status (cond
                                 any-aborted? :aborted
                                 any-failed?  :failure
                                 :else        :success)
                  ;; --- Post-build actions ---
                  post-actions (:post-actions effective-pipeline)
                  post-results (run-post-actions build-ctx build-status post-actions)
                  all-stage-results (into stage-results post-results)
                  ;; --- Artifact collection ---
                  artifact-patterns (:artifacts effective-pipeline)
                  collected-artifacts
                  (when (seq artifact-patterns)
                    (try
                      (let [artifact-root (get-in system [:config :artifacts :root] "artifacts")
                            artifact-dir (str artifact-root "/" job-id "/" build-number)]
                        (artifacts/collect-artifacts! ws artifact-dir artifact-patterns))
                      (catch Exception e
                        (log/warn "Artifact collection failed:" (.getMessage e))
                        nil)))
                  ;; Persist artifact metadata to DB
                  _ (when (and (seq collected-artifacts) (:db system))
                      (doseq [art collected-artifacts]
                        (artifact-store/save-artifact! (:db system)
                          {:build-id build-id
                           :filename (:filename art)
                           :path (:path art)
                           :size-bytes (:size-bytes art)
                           :content-type (:content-type art)
                           :sha256-hash (:sha256-hash art)})))
                  completed-at (now)
                  ;; Build result map (constructed before notifications so they can use it)
                  build-result (cond-> {:build-id build-id
                                        :job-id job-id
                                        :build-number build-number
                                        :build-status build-status
                                        :stage-results all-stage-results
                                        :workspace ws
                                        :started-at started-at
                                        :completed-at completed-at
                                        :pipeline-source pipeline-source
                                        :artifacts collected-artifacts}
                                 git-info (assoc :git-info git-info))
                  ;; --- Supply chain checks (after artifacts, before notifications) ---
                  _ (when (:db system)
                      (try
                        (when (ff/enabled? (:config system) :slsa-provenance)
                          (provenance/generate-provenance! system build-result))
                        (when (ff/enabled? (:config system) :sbom-generation)
                          (sbom/generate-sbom! system build-result))
                        (when (ff/enabled? (:config system) :container-scanning)
                          (vuln-scanner/scan-build! system build-result))
                        (when (ff/enabled? (:config system) :license-scanning)
                          (license-scanner/scan-licenses! system build-result))
                        (when (ff/enabled? (:config system) :artifact-signing)
                          (signing/sign-artifacts! system build-result))
                        (catch Exception e
                          (log/warn "Supply chain checks failed:" (.getMessage e)))))
                  ;; --- Notifications ---
                  _ (try
                      (notify/dispatch-notifications!
                        (:db system) build-result
                        (:notify effective-pipeline)
                        (:config system))
                      (catch Exception e
                        (log/warn "Notification dispatch failed:" (.getMessage e))))]
              (log/info "========================================")
              (log/info "Build" build-id "completed with status:" build-status)
              (log/info "========================================")
              (emit build-ctx :build-completed {:build-status build-status})
              build-result))))))))
