(ns chengis.engine.iac
  "Infrastructure-as-Code orchestration — project detection, command building,
   and plan output parsing for Terraform, Pulumi, and CloudFormation."
  (:require [chengis.db.iac-store :as iac-store]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Input validation
;; ---------------------------------------------------------------------------

(def ^:private safe-name-pattern
  "Pattern for safe IaC resource names (stack names, workspace names, etc.)."
  #"^[a-zA-Z0-9][a-zA-Z0-9._\-/]*$")

(defn- validate-name!
  "Validate an IaC name (stack, workspace, etc.). Throws on invalid input."
  [value label]
  (when (and value
             (or (str/blank? value)
                 (not (re-matches safe-name-pattern value))
                 (> (count value) 256)))
    (throw (ex-info (str "Invalid IaC " label) {label value}))))

(defn- shell-quote
  "Shell-quote a string value for safe embedding in commands."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

;; ---------------------------------------------------------------------------
;; Tool Detection
;; ---------------------------------------------------------------------------

(defn detect-tool-type
  "Detect IaC tool type from workspace directory.
   Returns :terraform, :pulumi, :cloudformation, or nil."
  [workspace-dir]
  (let [dir (io/file workspace-dir)]
    (when (.isDirectory dir)
      (let [files (seq (.listFiles dir))
            names (set (map #(.getName %) (or files [])))]
        (cond
          ;; Terraform: look for any *.tf files
          (some #(str/ends-with? (.getName %) ".tf") files)
          :terraform

          ;; Pulumi: look for Pulumi.yaml or Pulumi.yml
          (or (names "Pulumi.yaml") (names "Pulumi.yml"))
          :pulumi

          ;; CloudFormation: look for template files
          (or (names "template.json")
              (names "template.yaml")
              (names "template.yml")
              (some #(str/ends-with? (.getName %) ".template") files))
          :cloudformation

          :else nil)))))

(defn detect-iac-project
  "Scan workspace for IaC project files.
   Returns map with :tool-type and :working-dir, or nil."
  [workspace-dir]
  (when-let [tool-type (detect-tool-type workspace-dir)]
    (log/info "Detected IaC tool type" tool-type "in" workspace-dir)
    {:tool-type tool-type
     :working-dir workspace-dir}))

(defn ensure-project!
  "Auto-detect and create/update IaC project record for a job.
   Returns the project map, or nil if no IaC project detected."
  [ds org-id job-id workspace-dir]
  (let [existing (iac-store/get-project-by-job ds org-id job-id)
        detected (detect-iac-project workspace-dir)]
    (cond
      ;; No IaC detected and no existing project
      (and (nil? detected) (nil? existing))
      nil

      ;; New project detected, no existing record
      (and detected (nil? existing))
      (do (log/info "Creating IaC project for job" job-id "tool" (:tool-type detected))
          (iac-store/create-project! ds
            {:org-id org-id
             :job-id job-id
             :tool-type (:tool-type detected)
             :working-dir (:working-dir detected)
             :auto-detect true}))

      ;; Existing project, tool type changed
      (and detected existing
           (not= (name (:tool-type detected)) (name (:tool-type existing))))
      (do (log/info "Updating IaC project" (:id existing) "tool type from"
                    (:tool-type existing) "to" (:tool-type detected))
          (iac-store/update-project! ds (:id existing)
            {:tool-type (:tool-type detected)
             :working-dir (:working-dir detected)}
            :org-id org-id)
          (iac-store/get-project ds (:id existing) :org-id org-id))

      ;; Existing project, no change needed
      :else existing)))

;; ---------------------------------------------------------------------------
;; Command Building — Terraform
;; ---------------------------------------------------------------------------

(defn build-terraform-command
  "Build terraform CLI command for given action and options.
   Accepts a single opts map with :action plus optional keys.
   Actions: \"init\", \"validate\", \"plan\", \"apply\", \"destroy\", \"output\", \"show\"."
  [{:keys [action binary binary-path working-dir var-files vars workspace
           parallelism auto-init]
    :or {action "plan"}}]
  (let [binary-path (or binary binary-path "terraform")]
    (validate-name! workspace "workspace")
    (let [parts (cond-> [binary-path action]
                ;; No color for parseable output
                true
                (conj "-no-color")

                ;; Input false for CI
                (#{"init" "plan" "apply" "destroy"} action)
                (conj "-input=false")

                ;; JSON output for plan and show
                (= action "plan")
                (conj "-out=tfplan")

                ;; JSON output for show
                (= action "show")
                (conj "-json")

                ;; JSON output for output command
                (= action "output")
                (conj "-json")

                ;; Auto-approve for apply/destroy
                (#{"apply" "destroy"} action)
                (conj "-auto-approve")

                ;; Var files
                (seq var-files)
                (into (mapcat (fn [vf] ["-var-file" (shell-quote vf)]) var-files))

                ;; Individual variables
                (seq vars)
                (into (mapcat (fn [[k v]] ["-var" (shell-quote (str (name k) "=" v))]) vars))

                ;; Parallelism
                parallelism
                (conj (str "-parallelism=" parallelism)))
        base-cmd (str/join " " parts)
        ws-cmd (when workspace
                 (str binary-path " workspace select " (shell-quote workspace) " -no-color"))
        init-cmd (when (and auto-init (not= action "init"))
                   (str binary-path " init -no-color -input=false"))]
    (str/join " && "
      (remove nil? [init-cmd ws-cmd base-cmd])))))

;; ---------------------------------------------------------------------------
;; Command Building — Pulumi
;; ---------------------------------------------------------------------------

(defn build-pulumi-command
  "Build pulumi CLI command for given action and options.
   Accepts a single opts map with :action plus optional keys.
   Actions: \"preview\", \"up\", \"destroy\", \"output\", \"refresh\", \"stack-select\"."
  [{:keys [action binary binary-path working-dir stack stack-name backend-url vars]
    :or {action "preview"}}]
  (let [binary-path (or binary binary-path "pulumi")
        stack (or stack stack-name)]
    (validate-name! stack "stack-name")
    (let [parts (cond-> [binary-path]
                ;; Stack select is a compound command
                (= action "stack-select")
                (conj "stack" "select")

                ;; Normal action
                (not= action "stack-select")
                (conj action)

                ;; Stack name for stack-select
                (and (= action "stack-select") stack)
                (conj (shell-quote stack))

                ;; Stack for other actions
                (and (not= action "stack-select") stack)
                (conj "--stack" (shell-quote stack))

                ;; JSON output for preview and output
                (#{"preview" "output"} action)
                (conj "--json")

                ;; Yes flag for up, destroy, refresh
                (#{"up" "destroy" "refresh"} action)
                (conj "--yes")

                ;; Non-interactive
                true
                (conj "--non-interactive"))
        base-cmd (str/join " " parts)
        env-prefix (when backend-url
                     (str "PULUMI_BACKEND_URL=" (shell-quote backend-url) " "))]
    (str env-prefix base-cmd))))

;; ---------------------------------------------------------------------------
;; Command Building — CloudFormation
;; ---------------------------------------------------------------------------

(defn build-cloudformation-command
  "Build AWS CloudFormation CLI command for given action and options.
   Accepts a single opts map with :action plus optional keys.
   Actions: \"validate\", \"create\", \"update\", \"delete\", \"describe\", \"change-set\"."
  [{:keys [action binary binary-path stack-name template-file region capabilities parameters]
    :or {action "validate"}}]
  (let [binary-path (or binary binary-path "aws")]
    (validate-name! stack-name "stack-name")
    (let [sub-cmd (case action
                  "validate"   "cloudformation validate-template"
                  "create"     "cloudformation create-stack"
                  "update"     "cloudformation update-stack"
                  "delete"     "cloudformation delete-stack"
                  "describe"   "cloudformation describe-stacks"
                  "change-set" "cloudformation create-change-set"
                  (str "cloudformation " action))
        parts (cond-> [binary-path sub-cmd]
                ;; Stack name
                (and stack-name (not= action "validate"))
                (conj "--stack-name" (shell-quote stack-name))

                ;; Template file for create/update/validate/change-set
                (and template-file (#{"validate" "create" "update" "change-set"} action))
                (conj "--template-body" (str "file://" template-file))

                ;; Region
                region
                (conj "--region" region)

                ;; Capabilities (e.g., CAPABILITY_IAM)
                (seq capabilities)
                (conj "--capabilities" (str/join " " capabilities))

                ;; Change set name for change-set action
                (= action "change-set")
                (conj "--change-set-name"
                      (str "chengis-cs-" (System/currentTimeMillis)))

                ;; JSON output
                true
                (conj "--output" "json")

                ;; No paginate
                true
                (conj "--no-paginate"))]
    (str/join " " parts))))

;; ---------------------------------------------------------------------------
;; Plan Parsing — Terraform
;; ---------------------------------------------------------------------------

(defn parse-terraform-plan-summary
  "Parse terraform plan JSON output to extract resource counts.
   Expects output from 'terraform show -json tfplan'.
   Returns {:resources-add N :resources-change N :resources-destroy N :resources [...]}."
  [stdout]
  (try
    (let [parsed (json/read-str stdout :key-fn keyword)
          changes (or (:resource_changes parsed) [])
          resources (mapv (fn [rc]
                            {:resource-type (:type rc)
                             :name (:name rc)
                             :action (let [actions (get-in rc [:change :actions])]
                                       (cond
                                         (= actions ["create"]) "create"
                                         (= actions ["delete"]) "delete"
                                         (= actions ["update"]) "update"
                                         (= actions ["delete" "create"]) "replace"
                                         (= actions ["create" "delete"]) "replace"
                                         (= actions ["no-op"]) "no-op"
                                         :else (str/join "," (or actions ["unknown"]))))})
                          changes)
          counted (frequencies (map :action resources))]
      {:resources-add (+ (get counted "create" 0) (get counted "replace" 0))
       :resources-change (get counted "update" 0)
       :resources-destroy (get counted "delete" 0)
       :resources resources})
    (catch Exception e
      (log/warn "Failed to parse Terraform plan JSON:" (.getMessage e))
      {:resources-add 0 :resources-change 0 :resources-destroy 0 :resources []})))

;; ---------------------------------------------------------------------------
;; Plan Parsing — Pulumi
;; ---------------------------------------------------------------------------

(defn parse-pulumi-preview-summary
  "Parse pulumi preview JSON output to extract resource counts.
   Expects output from 'pulumi preview --json'.
   Returns {:resources-add N :resources-change N :resources-destroy N :resources [...]}."
  [stdout]
  (try
    (let [parsed (json/read-str stdout :key-fn keyword)
          steps (or (:steps parsed) [])
          resources (mapv (fn [step]
                            {:resource-type (:type step)
                             :name (or (:name step)
                                       (:urn step)
                                       "")
                             :action (case (:op step)
                                       "create" "create"
                                       "delete" "delete"
                                       "update" "update"
                                       "replace" "replace"
                                       "same" "no-op"
                                       (or (:op step) "unknown"))})
                          steps)
          counted (frequencies (map :action resources))]
      {:resources-add (+ (get counted "create" 0) (get counted "replace" 0))
       :resources-change (get counted "update" 0)
       :resources-destroy (get counted "delete" 0)
       :resources resources})
    (catch Exception e
      (log/warn "Failed to parse Pulumi preview JSON:" (.getMessage e))
      {:resources-add 0 :resources-change 0 :resources-destroy 0 :resources []})))

;; ---------------------------------------------------------------------------
;; Plan Parsing — CloudFormation
;; ---------------------------------------------------------------------------

(defn parse-cloudformation-changeset-summary
  "Parse CloudFormation change set JSON to extract resource changes.
   Expects output from 'aws cloudformation describe-change-set'.
   Returns {:resources-add N :resources-change N :resources-destroy N :resources [...]}."
  [stdout]
  (try
    (let [parsed (json/read-str stdout :key-fn keyword)
          changes (or (:Changes parsed) [])
          resources (mapv (fn [change]
                            (let [rc (:ResourceChange change)]
                              {:resource-type (:ResourceType rc)
                               :name (or (:LogicalResourceId rc) "")
                               :action (case (:Action rc)
                                         "Add" "create"
                                         "Modify" "update"
                                         "Remove" "delete"
                                         "Import" "create"
                                         (or (:Action rc) "unknown"))}))
                          changes)
          counted (frequencies (map :action resources))]
      {:resources-add (get counted "create" 0)
       :resources-change (get counted "update" 0)
       :resources-destroy (get counted "delete" 0)
       :resources resources})
    (catch Exception e
      (log/warn "Failed to parse CloudFormation change set JSON:" (.getMessage e))
      {:resources-add 0 :resources-change 0 :resources-destroy 0 :resources []})))

;; ---------------------------------------------------------------------------
;; Unified Parser Dispatch
;; ---------------------------------------------------------------------------

(defn parse-plan-summary
  "Parse plan output based on tool type. Dispatches to tool-specific parser."
  [tool-type stdout]
  (case (name tool-type)
    "terraform"      (parse-terraform-plan-summary stdout)
    "pulumi"         (parse-pulumi-preview-summary stdout)
    "cloudformation" (parse-cloudformation-changeset-summary stdout)
    {:resources-add 0 :resources-change 0 :resources-destroy 0 :resources []}))
