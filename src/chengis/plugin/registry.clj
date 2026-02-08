(ns chengis.plugin.registry
  "Central plugin registry for Chengis.
   Manages registration and lookup of step executors, pipeline formats,
   notifiers, artifact handlers, and SCM providers."
  (:require [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Registry state
;; ---------------------------------------------------------------------------

(defonce ^:private registry
  (atom {:plugins            {}   ;; name -> descriptor
         :step-executors     {}   ;; type-keyword -> StepExecutor instance
         :pipeline-formats   {}   ;; format-name -> PipelineFormat instance
         :notifiers          {}   ;; type-keyword -> Notifier instance
         :artifact-handlers  {}   ;; handler-name -> ArtifactHandler instance
         :scm-providers      {}   ;; type-keyword -> ScmProvider instance
         :status-reporters   {}}));; type-keyword -> ScmStatusReporter instance

;; ---------------------------------------------------------------------------
;; Plugin registration
;; ---------------------------------------------------------------------------

(defn register-plugin!
  "Register a plugin descriptor. Returns the descriptor."
  [descriptor]
  (let [name (:name descriptor)]
    (log/debug "Registering plugin:" name "v" (:version descriptor))
    (swap! registry assoc-in [:plugins name] descriptor)
    descriptor))

(defn deregister-plugin!
  "Remove a plugin from the registry."
  [plugin-name]
  (swap! registry update :plugins dissoc plugin-name))

;; ---------------------------------------------------------------------------
;; Step Executors
;; ---------------------------------------------------------------------------

(defn register-step-executor!
  "Register a step executor for a given step type (e.g., :shell, :docker)."
  [step-type executor]
  (log/debug "Registering step executor:" step-type)
  (swap! registry assoc-in [:step-executors step-type] executor))

(defn get-step-executor
  "Look up a step executor by type. Returns nil if not found."
  [step-type]
  (get-in @registry [:step-executors step-type]))

(defn list-step-executors
  "List all registered step executor types."
  []
  (keys (:step-executors @registry)))

;; ---------------------------------------------------------------------------
;; Pipeline Formats
;; ---------------------------------------------------------------------------

(defn register-pipeline-format!
  "Register a pipeline format handler (e.g., \"edn\", \"yaml\")."
  [format-name handler]
  (log/debug "Registering pipeline format:" format-name)
  (swap! registry assoc-in [:pipeline-formats format-name] handler))

(defn get-pipeline-format
  "Look up a pipeline format handler by name."
  [format-name]
  (get-in @registry [:pipeline-formats format-name]))

(defn list-pipeline-formats
  "List all registered pipeline format names."
  []
  (keys (:pipeline-formats @registry)))

(defn get-all-pipeline-formats
  "Get all registered pipeline format handlers as a seq of [name handler]."
  []
  (seq (:pipeline-formats @registry)))

;; ---------------------------------------------------------------------------
;; Notifiers
;; ---------------------------------------------------------------------------

(defn register-notifier!
  "Register a notifier for a given type (e.g., :console, :slack)."
  [notifier-type notifier]
  (log/debug "Registering notifier:" notifier-type)
  (swap! registry assoc-in [:notifiers notifier-type] notifier))

(defn get-notifier
  "Look up a notifier by type. Returns nil if not found."
  [notifier-type]
  (or (get-in @registry [:notifiers notifier-type])
      ;; Also try string version of keyword
      (get-in @registry [:notifiers (keyword notifier-type)])))

(defn list-notifiers
  "List all registered notifier types."
  []
  (keys (:notifiers @registry)))

;; ---------------------------------------------------------------------------
;; Artifact Handlers
;; ---------------------------------------------------------------------------

(defn register-artifact-handler!
  "Register an artifact handler (e.g., \"local\", \"s3\")."
  [handler-name handler]
  (log/debug "Registering artifact handler:" handler-name)
  (swap! registry assoc-in [:artifact-handlers handler-name] handler))

(defn get-artifact-handler
  "Look up an artifact handler by name."
  [handler-name]
  (get-in @registry [:artifact-handlers handler-name]))

;; ---------------------------------------------------------------------------
;; SCM Providers
;; ---------------------------------------------------------------------------

(defn register-scm-provider!
  "Register an SCM provider for a given type (e.g., :git)."
  [scm-type provider]
  (log/debug "Registering SCM provider:" scm-type)
  (swap! registry assoc-in [:scm-providers scm-type] provider))

(defn get-scm-provider
  "Look up an SCM provider by type."
  [scm-type]
  (get-in @registry [:scm-providers scm-type]))

;; ---------------------------------------------------------------------------
;; SCM Status Reporters
;; ---------------------------------------------------------------------------

(defn register-status-reporter!
  "Register an SCM status reporter for a given provider (e.g., :github, :gitlab)."
  [provider-type reporter]
  (log/debug "Registering status reporter:" provider-type)
  (swap! registry assoc-in [:status-reporters provider-type] reporter))

(defn get-status-reporter
  "Look up a status reporter by provider type."
  [provider-type]
  (get-in @registry [:status-reporters provider-type]))

(defn list-status-reporters
  "List all registered status reporter types."
  []
  (keys (:status-reporters @registry)))

(defn get-all-status-reporters
  "Get all registered status reporters as a seq of [provider reporter]."
  []
  (seq (:status-reporters @registry)))

;; ---------------------------------------------------------------------------
;; Introspection
;; ---------------------------------------------------------------------------

(defn list-plugins
  "List all registered plugin descriptors."
  []
  (vals (:plugins @registry)))

(defn get-plugin
  "Get a plugin descriptor by name."
  [plugin-name]
  (get-in @registry [:plugins plugin-name]))

(defn registry-summary
  "Return a summary of the registry for admin/debug purposes."
  []
  {:plugins           (count (:plugins @registry))
   :step-executors    (vec (keys (:step-executors @registry)))
   :pipeline-formats  (vec (keys (:pipeline-formats @registry)))
   :notifiers         (vec (keys (:notifiers @registry)))
   :artifact-handlers (vec (keys (:artifact-handlers @registry)))
   :scm-providers     (vec (keys (:scm-providers @registry)))
   :status-reporters  (vec (keys (:status-reporters @registry)))})

;; ---------------------------------------------------------------------------
;; Reset (for testing)
;; ---------------------------------------------------------------------------

(defn reset-registry!
  "Clear the entire registry. Useful for testing."
  []
  (reset! registry {:plugins {} :step-executors {} :pipeline-formats {}
                    :notifiers {} :artifact-handlers {} :scm-providers {}
                    :status-reporters {}}))
