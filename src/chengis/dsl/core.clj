(ns chengis.dsl.core
  "Pipeline DSL for defining CI/CD pipelines as Clojure data structures.

   Usage:
     (defpipeline my-app
       {:description \"My application pipeline\"}
       (stage \"Build\"
         (step \"Compile\" (sh \"mvn compile\")))
       (stage \"Test\"
         (parallel
           (step \"Unit\" (sh \"mvn test\"))
           (step \"Lint\" (sh \"mvn checkstyle:check\"))))
       (stage \"Deploy\"
         (when-branch \"main\"
           (step \"Deploy\" (sh \"./deploy.sh\" :env {\"ENV\" \"prod\"})))))")

;; Registry of defined pipelines
(defonce ^:private pipeline-registry (atom {}))

(defn sh
  "Define a shell command step action.
   Options:
     :env     - map of environment variables
     :dir     - working directory
     :timeout - timeout in milliseconds"
  [command & {:keys [env dir timeout]}]
  (cond-> {:type :shell
           :command command}
    env     (assoc :env env)
    dir     (assoc :dir dir)
    timeout (assoc :timeout timeout)))

(defn step
  "Define a named step with an action."
  [name action]
  (assoc action :step-name name))

(defn parallel
  "Mark a group of steps for parallel execution.
   Returns a stage fragment with :parallel? true."
  [& steps]
  {:parallel? true
   :steps (vec steps)})

(defn when-branch
  "Wrap steps with a branch condition."
  [branch & steps]
  (mapv #(assoc % :condition {:type :branch :value branch}) steps))

(defn when-param
  "Wrap steps with a parameter condition."
  [param value & steps]
  (mapv #(assoc % :condition {:type :param :param param :value value}) steps))

(defn stage
  "Define a named pipeline stage containing steps.
   Accepts individual steps, parallel groups, or conditional steps."
  [name & body]
  (let [;; Flatten the body: parallel groups contribute :parallel? and :steps,
        ;; when-branch returns vectors of steps, regular steps are maps
        items (reduce (fn [acc item]
                        (cond
                          ;; parallel group
                          (and (map? item) (:parallel? item))
                          (assoc acc :parallel? true
                                     :steps (into (:steps acc) (:steps item)))
                          ;; when-branch returns a vector
                          (vector? item)
                          (update acc :steps into item)
                          ;; regular step
                          (map? item)
                          (update acc :steps conj item)
                          :else acc))
                      {:stage-name name :parallel? false :steps []}
                      body)]
    items))

(defn build-pipeline
  "Construct a pipeline map from opts and stages."
  [pipeline-name opts stages]
  (let [base {:pipeline-name (clojure.core/name pipeline-name)
              :stages (vec stages)}]
    (cond-> base
      (:description opts)  (assoc :description (:description opts))
      (:parameters opts)   (assoc :parameters (:parameters opts))
      (:triggers opts)     (assoc :triggers (:triggers opts))
      (:source opts)       (assoc :source (:source opts)))))

(defn register-pipeline!
  "Register a pipeline in the global registry. Returns the pipeline."
  [pipeline]
  (swap! pipeline-registry assoc (:pipeline-name pipeline) pipeline)
  pipeline)

(defmacro defpipeline
  "Define and register a named pipeline.
   First argument after name can be an options map, followed by stage definitions."
  [pipeline-name & body]
  (let [[opts stages] (if (map? (first body))
                        [(first body) (rest body)]
                        [{} body])]
    `(register-pipeline! (build-pipeline '~pipeline-name ~opts [~@stages]))))

(defn get-pipeline
  "Look up a registered pipeline by name."
  [name]
  (get @pipeline-registry name))

(defn list-pipelines
  "List all registered pipeline names."
  []
  (keys @pipeline-registry))

(defn clear-registry!
  "Clear all registered pipelines. Useful for testing."
  []
  (reset! pipeline-registry {}))

(defn load-pipeline-file
  "Load and evaluate a pipeline definition file, returning the pipeline map.
   The file should contain a (defpipeline ...) form.
   Uses before/after snapshot to safely identify the newly registered pipeline."
  [path]
  (let [before (set (keys @pipeline-registry))]
    (load-file path)
    (let [after @pipeline-registry
          new-keys (remove before (keys after))]
      (when (seq new-keys)
        (get after (first new-keys))))))
