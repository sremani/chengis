(ns chengis.engine.executor
  "Pipeline execution engine. Runs stages sequentially, steps within a stage
   either sequentially or in parallel, handles failures and abort signals."
  (:require [clojure.core.async :refer [<!! thread]]
            [chengis.dsl.chengisfile :as chengisfile]
            [chengis.engine.git :as git]
            [chengis.engine.process :as process]
            [chengis.engine.workspace :as workspace]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

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
      (let [result (process/execute-command
                     {:command (:command step-def)
                      :dir (:workspace build-ctx)
                      :env (merge (:env build-ctx) (:env step-def))
                      :timeout (:timeout step-def)})
            status (if (zero? (:exit-code result)) :success :failure)]
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
          step-result)))))

(defn- run-steps-sequential
  "Run steps one by one. Stops on first failure."
  [build-ctx steps]
  (reduce (fn [results step-def]
            (let [result (run-step build-ctx step-def)]
              (let [updated (conj results result)]
                (if (= :failure (:step-status result))
                  (reduced updated)
                  updated))))
          []
          steps))

(defn- run-steps-parallel
  "Run steps concurrently using core.async threads. Waits for all to complete."
  [build-ctx steps]
  (let [result-chans (mapv (fn [step-def]
                             (thread (run-step build-ctx step-def)))
                           steps)
        results (mapv <!! result-chans)]
    results))

(defn run-stage
  "Execute a pipeline stage. Returns a stage result map."
  [build-ctx stage-def]
  (let [stage-name (:stage-name stage-def)
        started-at (now)
        ;; Add current stage name to context so steps can reference it
        build-ctx (assoc build-ctx :current-stage stage-name)]
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
      (let [step-results (if (:parallel? stage-def)
                           (run-steps-parallel build-ctx (:steps stage-def))
                           (run-steps-sequential build-ctx (:steps stage-def)))
            any-failed? (some #(= :failure (:step-status %)) step-results)
            all-skipped? (every? #(= :skipped (:step-status %)) step-results)
            stage-status (cond
                           any-failed?  :failure
                           all-skipped? :skipped
                           :else        :success)]
        (log/info "Stage" stage-name "completed with status:" stage-status)
        (emit build-ctx :stage-completed {:stage-name stage-name :stage-status stage-status})
        {:stage-name stage-name
         :stage-status stage-status
         :step-results step-results
         :started-at started-at
         :completed-at (now)}))))

(defn run-build
  "Execute a complete build for a pipeline definition.

   Arguments:
     system      - system map containing :config and optionally :db
     pipeline    - pipeline definition map (from DSL)
     params      - build parameters map (supports optional :event-fn for live updates)

   Returns a build result map with status, stage results, and timing info."
  [system pipeline params]
  (let [build-id (util/generate-id)
        job-id (or (:job-id params) (:pipeline-name pipeline))
        build-number (or (:build-number params) 1)
        workspace-root (get-in system [:config :workspace :root] "workspaces")
        ws (workspace/create-workspace workspace-root job-id build-number)
        started-at (now)
        ;; Minimal build-ctx for emitting early events
        early-ctx {:build-id build-id :event-fn (:event-fn params)}]
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
        ;; --- Chengisfile detection (Pipeline as Code) ---
        (let [cf-result (when (and git-result (:success? git-result)
                                   (chengisfile/chengisfile-exists? ws))
                          (log/info "Chengisfile detected in workspace")
                          (emit early-ctx :chengisfile-detected {:workspace ws})
                          (chengisfile/parse-chengisfile
                            (chengisfile/chengisfile-path ws)))
              ;; Build effective pipeline: Chengisfile replaces stages/description
              effective-pipeline
              (if (and cf-result (:pipeline cf-result))
                (let [cf-pipeline (:pipeline cf-result)]
                  (log/info "Using Chengisfile pipeline:"
                            (count (:stages cf-pipeline)) "stages")
                  (cond-> (assoc pipeline :stages (:stages cf-pipeline))
                    (:description cf-pipeline)
                    (assoc :description (:description cf-pipeline))))
                (do
                  (when (and cf-result (:error cf-result))
                    (log/warn "Chengisfile parse error, using server pipeline:"
                              (:error cf-result))
                    (emit early-ctx :chengisfile-error
                          {:error (:error cf-result)}))
                  pipeline))
              pipeline-source (if (and cf-result (:pipeline cf-result))
                                "chengisfile" "server")]
          ;; --- Normal build execution ---
          (let [git-env (when git-info
                          {"GIT_BRANCH"       (:branch git-info)
                           "GIT_COMMIT"       (:commit git-info)
                           "GIT_COMMIT_SHORT" (:commit-short git-info)
                           "GIT_AUTHOR"       (:author git-info)
                           "GIT_MESSAGE"      (:message git-info)})
                build-env (merge {"BUILD_ID" build-id
                                  "BUILD_NUMBER" (str build-number)
                                  "JOB_NAME" job-id
                                  "WORKSPACE" ws}
                                 git-env
                                 (:env params))
                build-ctx {:build-id build-id
                           :job-id job-id
                           :build-number build-number
                           :workspace ws
                           :parameters (merge (or (:parameters params) {})
                                              (when git-info
                                                {:branch (:branch git-info)}))
                           :env build-env
                           :event-fn (:event-fn params)}]
            (emit build-ctx :build-started {:job-id job-id :build-number build-number})
            (let [stage-results
                  (reduce (fn [results stage-def]
                            (let [result (run-stage build-ctx stage-def)]
                              (let [updated (conj results result)]
                                (if (= :failure (:stage-status result))
                                  (do
                                    (log/error "Pipeline stopped: stage"
                                               (:stage-name stage-def) "failed")
                                    (reduced updated))
                                  updated))))
                          []
                          (:stages effective-pipeline))
                  any-failed? (some #(= :failure (:stage-status %)) stage-results)
                  build-status (if any-failed? :failure :success)
                  completed-at (now)]
              (log/info "========================================")
              (log/info "Build" build-id "completed with status:" build-status)
              (log/info "========================================")
              (emit build-ctx :build-completed {:build-status build-status})
              (cond-> {:build-id build-id
                       :job-id job-id
                       :build-number build-number
                       :build-status build-status
                       :stage-results stage-results
                       :workspace ws
                       :started-at started-at
                       :completed-at completed-at
                       :pipeline-source pipeline-source}
                git-info (assoc :git-info git-info)))))))))
