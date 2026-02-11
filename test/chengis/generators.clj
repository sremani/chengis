(ns chengis.generators
  "Shared test.check generators for property-based testing across Chengis.
   Provides domain-specific generators for IaC resources, tool types,
   plan JSON structures, config maps, and EDN-safe values."
  (:require [clojure.test.check.generators :as gen]
            [clojure.data.json :as json]))

;; ---------------------------------------------------------------------------
;; Identifiers & Names
;; ---------------------------------------------------------------------------

(def gen-id
  "UUID-style identifiers used throughout Chengis."
  (gen/fmap str gen/uuid))

(def gen-valid-name
  "Names matching validate-name! regex: ^[a-zA-Z0-9][a-zA-Z0-9._\\-/]*$
   with length 1–51 (well within the 256-char limit)."
  (gen/fmap (fn [[first-char rest-chars]]
              (apply str first-char rest-chars))
    (gen/tuple
      (gen/elements (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
      (gen/vector
        (gen/elements (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-/"))
        0 50))))

(def gen-invalid-name
  "Names that should fail validate-name! — blank, bad start char, too long, or special chars."
  (gen/one-of
    [(gen/return "")
     (gen/fmap #(str "." %) gen/string-alphanumeric)      ;; starts with dot
     (gen/fmap (fn [_] (apply str (repeat 257 "a")))      ;; too long (>256)
               gen/nat)
     (gen/fmap #(str "a" % "b")                           ;; contains bad chars
               (gen/elements [" " "!" "@" "#" "$" "%" "^" "&" "*"
                              "(" ")" "+" "=" "[" "]" "{" "}" "<"
                              ">" "|" "\\"]))]))

;; ---------------------------------------------------------------------------
;; IaC Tool Types & Actions
;; ---------------------------------------------------------------------------

(def gen-tool-type
  "Generator for IaC tool type strings."
  (gen/elements ["terraform" "pulumi" "cloudformation"]))

(def gen-tool-type-keyword
  "Generator for IaC tool type keywords."
  (gen/elements [:terraform :pulumi :cloudformation]))

(def gen-terraform-action
  "Actions supported by the Terraform executor."
  (gen/elements ["init" "validate" "plan" "apply" "destroy" "output" "show"]))

(def gen-pulumi-action
  "Actions supported by the Pulumi executor."
  (gen/elements ["preview" "up" "destroy" "output" "refresh" "stack-select"]))

(def gen-cloudformation-action
  "Actions supported by the CloudFormation executor."
  (gen/elements ["validate" "create" "update" "delete" "describe" "change-set"]))

(def gen-resource-action
  "Resource-level actions in plan output."
  (gen/elements ["create" "update" "delete" "replace" "no-op"]))

;; ---------------------------------------------------------------------------
;; Resource Types
;; ---------------------------------------------------------------------------

(def gen-terraform-resource-type
  "Common Terraform resource type strings."
  (gen/elements ["aws_instance" "aws_s3_bucket" "aws_rds_instance"
                 "aws_lambda_function" "aws_vpc" "aws_subnet"
                 "aws_security_group" "aws_iam_role" "aws_dynamodb_table"
                 "aws_ecs_service" "aws_lb" "aws_sqs_queue"
                 "google_compute_instance" "google_storage_bucket"
                 "azurerm_virtual_machine" "azurerm_storage_account"]))

(def gen-pulumi-resource-type
  "Pulumi-format resource types (provider:module:Type)."
  (gen/elements ["aws:ec2:Instance" "aws:s3:Bucket" "aws:rds:Instance"
                 "aws:lambda:Function" "aws:dynamodb:Table"
                 "gcp:compute:Instance" "gcp:storage:Bucket"
                 "azure:compute:VirtualMachine" "azure:storage:Account"]))

(def gen-cloudformation-resource-type
  "CloudFormation-format resource types (AWS::Service::Resource)."
  (gen/elements ["AWS::EC2::Instance" "AWS::S3::Bucket" "AWS::RDS::DBInstance"
                 "AWS::Lambda::Function" "AWS::DynamoDB::Table"
                 "AWS::ECS::Service" "AWS::ElasticLoadBalancingV2::LoadBalancer"]))

;; ---------------------------------------------------------------------------
;; Plan JSON Generators
;; ---------------------------------------------------------------------------

(def gen-terraform-resource-change
  "A single resource change entry in Terraform plan JSON."
  (gen/let [rtype gen-terraform-resource-type
            rname gen/string-alphanumeric
            action gen-resource-action]
    {:type rtype
     :name rname
     :change {:actions (case action
                         "create"  ["create"]
                         "delete"  ["delete"]
                         "update"  ["update"]
                         "replace" ["delete" "create"]
                         "no-op"   ["no-op"]
                         ["unknown"])}}))

(def gen-terraform-plan-json
  "Valid Terraform plan JSON string from 'terraform show -json'."
  (gen/fmap (fn [changes]
              (json/write-str {:resource_changes changes}))
            (gen/vector gen-terraform-resource-change 0 10)))

(def gen-pulumi-step
  "A single step entry in Pulumi preview JSON."
  (gen/let [rtype gen-pulumi-resource-type
            rname gen/string-alphanumeric
            op (gen/elements ["create" "delete" "update" "replace" "same"])]
    {:type rtype
     :name rname
     :urn (str "urn:pulumi:stack::" rtype "::" rname)
     :op op}))

(def gen-pulumi-preview-json
  "Valid Pulumi preview JSON string."
  (gen/fmap (fn [steps]
              (json/write-str {:steps steps}))
            (gen/vector gen-pulumi-step 0 10)))

(def gen-cloudformation-change
  "A single change entry in CloudFormation describe-change-set JSON."
  (gen/let [rtype gen-cloudformation-resource-type
            logical-id gen/string-alphanumeric
            cf-action (gen/elements ["Add" "Modify" "Remove" "Import"])]
    {:ResourceChange {:ResourceType rtype
                      :LogicalResourceId logical-id
                      :Action cf-action}}))

(def gen-cloudformation-changeset-json
  "Valid CloudFormation change set JSON string."
  (gen/fmap (fn [changes]
              (json/write-str {:Changes changes}))
            (gen/vector gen-cloudformation-change 0 10)))

;; ---------------------------------------------------------------------------
;; Parsed Resource
;; ---------------------------------------------------------------------------

(def gen-parsed-resource
  "A parsed resource map as returned by parse-*-plan-summary :resources."
  (gen/let [rtype gen-terraform-resource-type
            rname gen/string-alphanumeric
            action gen-resource-action]
    {:resource-type rtype
     :name rname
     :action action}))

(def gen-parsed-resources
  "Vector of parsed resources for cost estimation."
  (gen/vector gen-parsed-resource 0 15))

;; ---------------------------------------------------------------------------
;; Terraform State JSON
;; ---------------------------------------------------------------------------

(def gen-state-resource
  "A resource entry for Terraform state JSON."
  (gen/let [rtype gen-terraform-resource-type
            rname gen/string-alphanumeric]
    {:type rtype
     :name rname
     :instances []}))

(def gen-terraform-state-json
  "Valid Terraform state JSON string."
  (gen/fmap (fn [resources]
              (json/write-str {:version 4
                               :terraform_version "1.5.0"
                               :resources resources}))
            (gen/vector gen-state-resource 0 8)))

;; ---------------------------------------------------------------------------
;; Configuration / Environment
;; ---------------------------------------------------------------------------

(def gen-env-value
  "Values as they come from System/getenv — always strings."
  (gen/one-of
    [(gen/return "true")
     (gen/return "false")
     (gen/fmap str gen/nat)                            ;; numeric strings
     gen/string-alphanumeric                           ;; plain strings
     (gen/fmap #(str ":" %) gen/string-alphanumeric)])) ;; keyword-like

(def gen-nested-map
  "Nested maps suitable for deep-merge testing.
   Always produces a map at the top level (leaf values only appear as map values)."
  (gen/recursive-gen
    (fn [inner]
      (gen/map gen/keyword inner {:max-elements 4}))
    (gen/map gen/keyword
             (gen/one-of [gen/string-alphanumeric
                          gen/nat
                          gen/boolean])
             {:max-elements 3})))

;; ---------------------------------------------------------------------------
;; EDN-safe values
;; ---------------------------------------------------------------------------

(def gen-edn-safe
  "Values that survive EDN serialization round-trip (no functions, readers, etc.).
   Excludes nil and false at the top level because serialize-edn uses (when data ...)
   which treats nil and false as falsy and returns nil."
  (gen/recursive-gen
    (fn [inner]
      (gen/one-of
        [(gen/vector inner 0 4)
         (gen/map gen/keyword inner {:max-elements 3})]))
    (gen/one-of
      [gen/nat
       gen/keyword
       gen/string-alphanumeric
       (gen/return true)])))

;; ---------------------------------------------------------------------------
;; Shell command options
;; ---------------------------------------------------------------------------

(def gen-terraform-opts
  "Option maps for build-terraform-command."
  (gen/let [action gen-terraform-action
            workspace (gen/one-of [(gen/return nil) gen-valid-name])
            parallelism (gen/one-of [(gen/return nil) (gen/choose 1 20)])]
    (cond-> {:action action}
      workspace   (assoc :workspace workspace)
      parallelism (assoc :parallelism parallelism))))

(def gen-pulumi-opts
  "Option maps for build-pulumi-command."
  (gen/let [action gen-pulumi-action
            stack (gen/one-of [(gen/return nil) gen-valid-name])
            backend-url (gen/one-of [(gen/return nil) (gen/return "s3://my-bucket")])]
    (cond-> {:action action}
      stack       (assoc :stack stack)
      backend-url (assoc :backend-url backend-url))))

(def gen-cloudformation-opts
  "Option maps for build-cloudformation-command."
  (gen/let [action gen-cloudformation-action
            stack-name (gen/one-of [(gen/return nil) gen-valid-name])
            region (gen/one-of [(gen/return nil) (gen/return "us-east-1")])
            template-file (gen/one-of [(gen/return nil) (gen/return "template.json")])]
    (cond-> {:action action}
      stack-name    (assoc :stack-name stack-name)
      region        (assoc :region region)
      template-file (assoc :template-file template-file))))

;; ---------------------------------------------------------------------------
;; Cost estimation
;; ---------------------------------------------------------------------------

(def gen-cost-estimate
  "A cost estimate map as returned by estimate-plan-cost."
  (gen/let [monthly (gen/double* {:min 0.0 :max 10000.0 :NaN? false :infinite? false})
            hourly (gen/double* {:min 0.0 :max 15.0 :NaN? false :infinite? false})
            resource-count (gen/choose 0 10)]
    {:total-monthly monthly
     :total-hourly hourly
     :currency "USD"
     :estimation-method "builtin"
     :resources (vec (repeat resource-count {:resource-type "aws_instance"
                                              :monthly (/ monthly (max 1 resource-count))
                                              :action "create"
                                              :description "EC2 instance"}))}))

;; ---------------------------------------------------------------------------
;; Strings for security testing
;; ---------------------------------------------------------------------------

(def gen-adversarial-string
  "Strings likely to break shell quoting or HTML escaping."
  (gen/one-of
    [gen/string
     (gen/return "'")
     (gen/return "\"")
     (gen/return "'\\''")
     (gen/return "$(whoami)")
     (gen/return "`whoami`")
     (gen/return "; rm -rf /")
     (gen/return "' ; echo pwned ; '")
     (gen/return "a'b\"c\\d$e`f")
     (gen/return "<script>alert('xss')</script>")
     (gen/return "a&b<c>d\"e'f")
     (gen/return "")
     (gen/return "\n\r\t")
     (gen/fmap #(apply str (repeat 1000 %)) (gen/elements ["a" "'" "\""]))]))

;; ---------------------------------------------------------------------------
;; Auth & RBAC
;; ---------------------------------------------------------------------------

(def gen-role
  "Valid Chengis role strings."
  (gen/elements ["admin" "developer" "viewer"]))

(def gen-role-keyword
  "Valid Chengis role keywords."
  (gen/elements [:admin :developer :viewer]))

(def gen-valid-username
  "Usernames matching [a-zA-Z0-9_-]{2,64}."
  (gen/fmap (fn [[first-char rest-chars]]
              (apply str first-char rest-chars))
    (gen/tuple
      (gen/elements (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
      (gen/vector
        (gen/elements (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"))
        1 63))))

(def gen-invalid-username
  "Usernames that should fail validation."
  (gen/one-of
    [(gen/return "")                                         ;; too short
     (gen/return "x")                                        ;; too short (1 char)
     (gen/fmap (fn [_] (apply str (repeat 65 "a"))) gen/nat) ;; too long (>64)
     (gen/fmap #(str "ab" % "cd")                            ;; contains bad chars
               (gen/elements [" " "!" "@" "#" "$" "%" "^" "&"
                              "(" ")" "+" "=" "." "/"]))]))

(def gen-valid-password
  "Passwords meeting minimum 8-char requirement."
  (gen/fmap (fn [[base extra]]
              (str base extra))
    (gen/tuple
      (gen/fmap #(apply str %) (gen/vector gen/char-alphanumeric 8 8))
      gen/string-alphanumeric)))

(def gen-short-password
  "Passwords that are too short (<8 chars)."
  (gen/fmap #(apply str %)
    (gen/vector gen/char-alphanumeric 0 7)))

(def gen-scope
  "Valid API token scope strings."
  (gen/elements ["build:trigger" "build:read" "build:cancel"
                 "job:read" "job:create" "job:delete"
                 "secret:read" "secret:write"
                 "agent:read" "agent:register"
                 "admin:*"]))

(def gen-scope-set
  "Set of API token scopes."
  (gen/fmap set (gen/vector gen-scope 0 5)))

;; ---------------------------------------------------------------------------
;; DAG
;; ---------------------------------------------------------------------------

(def gen-dag-stage-names
  "A set of 2-6 unique stage names."
  (gen/fmap (fn [n]
              (mapv #(str "stage-" %) (range n)))
    (gen/choose 2 6)))

(def gen-valid-dag
  "A valid (acyclic) DAG as {stage-name #{dependencies}}.
   Uses a topological ordering to ensure acyclicity: each stage can only
   depend on stages that come before it in the ordering."
  (gen/bind gen-dag-stage-names
    (fn [names]
      (gen/fmap
        (fn [dep-flags]
          (into {}
            (map-indexed
              (fn [i name]
                ;; Stage i can depend on stages 0..(i-1)
                (let [possible-deps (subvec names 0 i)
                      ;; dep-flags for this stage — one bool per possible dep
                      flags (nth dep-flags i)
                      deps (set (keep-indexed
                                  (fn [j flag] (when flag (nth possible-deps j)))
                                  flags))]
                  [name deps]))
              names)))
        ;; For each stage i, generate (i) booleans for dependency choices
        (apply gen/tuple
          (map (fn [i]
                 (if (zero? i)
                   (gen/return [])
                   (gen/vector gen/boolean i)))
               (range (count names))))))))

(def gen-stages-with-deps
  "Stage definition maps with :stage-name and optional :depends-on, forming a valid DAG."
  (gen/fmap
    (fn [dag]
      (mapv (fn [[stage-name deps]]
              (cond-> {:stage-name stage-name
                       :steps [{:type :shell :command "echo test"}]}
                (seq deps) (assoc :depends-on (vec deps))))
            dag))
    gen-valid-dag))

(def gen-stages-without-deps
  "Stage definitions with no :depends-on (sequential execution)."
  (gen/fmap
    (fn [names]
      (mapv (fn [n] {:stage-name n
                     :steps [{:type :shell :command "echo test"}]})
            names))
    (gen/vector (gen/not-empty gen/string-alphanumeric) 1 5)))

;; ---------------------------------------------------------------------------
;; Matrix builds
;; ---------------------------------------------------------------------------

(def gen-dimension-values
  "A non-empty vector of string values for a matrix dimension."
  (gen/vector (gen/not-empty gen/string-alphanumeric) 1 4))

(def gen-dimension-name
  "Keyword name for a matrix dimension."
  (gen/elements [:os :jdk :arch :node :python :ruby]))

(def gen-matrix-dimensions
  "A map of 1-3 dimensions, each with 1-4 values."
  (gen/bind (gen/choose 1 3)
    (fn [n]
      (gen/fmap (fn [pairs]
                  (into {} pairs))
        (gen/vector
          (gen/tuple gen-dimension-name gen-dimension-values)
          n)))))

(def gen-combination
  "A single matrix combination map (dim-keyword -> string value)."
  (gen/fmap
    (fn [dims]
      (reduce-kv (fn [m k vs] (assoc m k (first vs))) {} dims))
    gen-matrix-dimensions))

;; ---------------------------------------------------------------------------
;; Vulnerability scanning
;; ---------------------------------------------------------------------------

(def gen-severity
  "Vulnerability severity string."
  (gen/elements ["CRITICAL" "HIGH" "MEDIUM" "LOW"]))

(def gen-trivy-vuln
  "A single Trivy vulnerability entry."
  (gen/fmap (fn [[sev id]] {:Severity sev :VulnerabilityID (str "CVE-2024-" id)})
    (gen/tuple gen-severity (gen/choose 10000 99999))))

(def gen-trivy-json
  "Valid Trivy JSON output string."
  (gen/fmap (fn [vulns]
              (json/write-str {:Results [{:Vulnerabilities vulns}]}))
    (gen/vector gen-trivy-vuln 0 20)))

(def gen-grype-match
  "A single Grype match entry."
  (gen/fmap (fn [[sev id]] {:vulnerability {:severity sev :id (str "CVE-2024-" id)}})
    (gen/tuple gen-severity (gen/choose 10000 99999))))

(def gen-grype-json
  "Valid Grype JSON output string."
  (gen/fmap (fn [matches]
              (json/write-str {:matches matches}))
    (gen/vector gen-grype-match 0 20)))

(def gen-vuln-counts
  "Vulnerability count map."
  (gen/let [critical (gen/choose 0 10)
            high (gen/choose 0 10)
            medium (gen/choose 0 10)
            low (gen/choose 0 10)]
    {:critical critical
     :high high
     :medium medium
     :low low
     :total (+ critical high medium low)}))

(def gen-severity-threshold
  "Threshold severity strings."
  (gen/elements ["critical" "high" "medium" "low"]))

;; ---------------------------------------------------------------------------
;; Analytics
;; ---------------------------------------------------------------------------

(def gen-build-status
  "Build status string."
  (gen/elements ["success" "failure" "aborted"]))

(def gen-iso-timestamp
  "ISO-8601 timestamp string in 2024."
  (gen/let [month (gen/choose 1 12)
            day (gen/choose 1 28)
            hour (gen/choose 0 23)
            minute (gen/choose 0 59)
            second (gen/choose 0 59)]
    (format "2024-%02d-%02dT%02d:%02d:%02dZ" month day hour minute second)))

(def gen-timestamp-pair
  "A pair of ISO timestamps where start <= end."
  (gen/let [month (gen/choose 1 12)
            day (gen/choose 1 28)
            hour (gen/choose 0 20)
            minute (gen/choose 0 59)
            dur-minutes (gen/choose 1 120)]
    (let [start-str (format "2024-%02d-%02dT%02d:%02d:00Z" month day hour minute)
          total-minutes (+ (* hour 60) minute dur-minutes)
          end-hour (min 23 (quot total-minutes 60))
          end-min (mod total-minutes 60)]
      {:started-at start-str
       :completed-at (format "2024-%02d-%02dT%02d:%02d:00Z"
                       month day end-hour end-min)})))

(def gen-build-record
  "A build record map for analytics testing."
  (gen/let [status gen-build-status
            times gen-timestamp-pair]
    (merge {:status status} times)))

(def gen-build-records
  "Vector of build records for analytics aggregation."
  (gen/vector gen-build-record 0 20))

(def gen-success-rate
  "A success rate between 0.0 and 1.0."
  (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false}))

(def gen-sorted-durations
  "Sorted vector of positive duration numbers."
  (gen/fmap (fn [vs] (vec (sort vs)))
    (gen/vector (gen/double* {:min 0.1 :max 3600.0 :NaN? false :infinite? false})
                0 20)))

;; ---------------------------------------------------------------------------
;; License scanning
;; ---------------------------------------------------------------------------

(def gen-license-id
  "SPDX license identifier."
  (gen/elements ["MIT" "Apache-2.0" "GPL-3.0" "BSD-2-Clause" "BSD-3-Clause"
                 "ISC" "MPL-2.0" "LGPL-2.1" "AGPL-3.0" "Unlicense"]))

(def gen-license-component
  "A component with license info for policy evaluation."
  (gen/let [name (gen/not-empty gen/string-alphanumeric)
            version (gen/fmap #(str (inc %) ".0.0") gen/nat)
            license gen-license-id]
    {:component-name name
     :component-version version
     :license-id license}))

(def gen-license-policy-entry
  "A license policy entry (allow or deny)."
  (gen/let [license gen-license-id
            action (gen/elements ["allow" "deny"])]
    {:license-id license :action action}))

(def gen-license-policy
  "A vector of license policy entries."
  (gen/vector gen-license-policy-entry 0 8))

(def gen-cyclonedx-sbom-json
  "Valid CycloneDX SBOM JSON string."
  (gen/fmap
    (fn [components]
      (json/write-str
        {:bomFormat "CycloneDX"
         :specVersion "1.4"
         :components
         (mapv (fn [c]
                 {:name (:component-name c)
                  :version (:component-version c)
                  :licenses [{:license {:id (:license-id c)}}]})
               components)}))
    (gen/vector gen-license-component 0 10)))

;; ---------------------------------------------------------------------------
;; Provenance & signing
;; ---------------------------------------------------------------------------

(def gen-sha256-hash
  "A 64-char lowercase hex string mimicking a SHA-256 hash."
  (gen/fmap (fn [chars] (apply str chars))
    (gen/vector (gen/elements (vec "0123456789abcdef")) 64)))

(def gen-artifact-with-hash
  "An artifact map with filename and SHA-256 hash."
  (gen/let [filename (gen/fmap #(str % ".jar") (gen/not-empty gen/string-alphanumeric))
            hash gen-sha256-hash]
    {:filename filename :sha256-hash hash}))

(def gen-build-result-map
  "A build result map for provenance generation."
  (gen/let [build-id gen-id
            job-id (gen/not-empty gen/string-alphanumeric)
            build-number gen/nat
            times gen-timestamp-pair]
    {:build-id build-id
     :job-id job-id
     :build-number build-number
     :pipeline-source "docker.io/myorg/app:latest"
     :started-at (:started-at times)
     :completed-at (:completed-at times)
     :git-info {:parameters {}}}))

(def gen-file-path-safe
  "A safe file path string (no shell metacharacters)."
  (gen/fmap (fn [parts] (str "/tmp/" (clojure.string/join "/" parts)))
    (gen/vector (gen/not-empty gen/string-alphanumeric) 1 3)))

;; ---------------------------------------------------------------------------
;; SBOM & artifacts
;; ---------------------------------------------------------------------------

(def gen-file-extension
  "Common file extensions."
  (gen/elements [".jar" ".zip" ".tar.gz" ".html" ".json" ".xml"
                 ".txt" ".log" ".css" ".js" ".pdf" ".png" ".jpg" ".svg"]))

(def gen-filename-with-extension
  "A filename with a known extension."
  (gen/fmap (fn [[name ext]] (str name ext))
    (gen/tuple (gen/not-empty gen/string-alphanumeric) gen-file-extension)))

(def gen-filename-unknown-ext
  "A filename with an unknown extension."
  (gen/fmap (fn [name] (str name ".xyz123"))
    (gen/not-empty gen/string-alphanumeric)))

;; ---------------------------------------------------------------------------
;; IaC Cost estimation
;; ---------------------------------------------------------------------------

(def gen-terraform-resource-type
  "Known Terraform resource type strings."
  (gen/elements ["aws_instance" "aws_s3_bucket" "aws_rds_instance" "aws_lambda_function"
                 "aws_dynamodb_table" "aws_ecs_service" "aws_lb" "aws_vpc"
                 "google_compute_instance" "google_storage_bucket"
                 "azurerm_virtual_machine" "azurerm_storage_account"]))

(def gen-pulumi-resource-type
  "Pulumi resource type strings (colon-separated format)."
  (gen/elements ["aws:s3:Bucket" "aws:ec2:Instance" "aws:rds:Instance"
                 "aws:lambda:Function" "aws:dynamodb:Table"
                 "gcp:compute:Instance" "gcp:storage:Bucket"
                 "azure:compute:VirtualMachine" "azure:storage:Account"]))

(def gen-cloudformation-resource-type
  "CloudFormation resource type strings (double-colon format)."
  (gen/elements ["AWS::S3::Bucket" "AWS::EC2::Instance" "AWS::RDS::DBInstance"
                 "AWS::Lambda::Function" "AWS::DynamoDB::Table"
                 "AWS::ECS::Service" "AWS::ElasticLoadBalancingV2::LoadBalancer"]))

(def gen-resource-action
  "IaC resource action strings."
  (gen/elements ["create" "update" "delete" "replace" "no-op"]))

(def gen-terraform-plan-json
  "Valid Terraform plan JSON (terraform show -json output)."
  (gen/fmap
    (fn [resources]
      (json/write-str
        {:resource_changes
         (mapv (fn [[rt nm action]]
                 {:type rt :name nm
                  :change {:actions [action]}})
               resources)}))
    (gen/vector
      (gen/tuple gen-terraform-resource-type
                 (gen/not-empty gen/string-alphanumeric)
                 (gen/elements ["create" "delete" "update" "no-op"]))
      0 10)))

(def gen-pulumi-preview-json
  "Valid Pulumi preview JSON output."
  (gen/fmap
    (fn [steps]
      (json/write-str
        {:steps
         (mapv (fn [[rt nm op]]
                 {:type rt :name nm :op op
                  :urn (str "urn:pulumi:stack::project::" rt "::" nm)})
               steps)}))
    (gen/vector
      (gen/tuple (gen/not-empty gen/string-alphanumeric)
                 (gen/not-empty gen/string-alphanumeric)
                 (gen/elements ["create" "delete" "update" "replace" "same"]))
      0 10)))

(def gen-cf-changeset-json
  "Valid CloudFormation describe-change-set JSON output."
  (gen/fmap
    (fn [changes]
      (json/write-str
        {:Changes
         (mapv (fn [[rt lid action]]
                 {:ResourceChange {:ResourceType rt
                                   :LogicalResourceId lid
                                   :Action action}})
               changes)}))
    (gen/vector
      (gen/tuple gen-cloudformation-resource-type
                 (gen/not-empty gen/string-alphanumeric)
                 (gen/elements ["Add" "Modify" "Remove"]))
      0 10)))

;; ---------------------------------------------------------------------------
;; Build Compare
;; ---------------------------------------------------------------------------

(def gen-stage-record
  "A stage record for build comparison."
  (gen/let [name (gen/not-empty gen/string-alphanumeric)
            status (gen/elements ["success" "failure" "aborted"])
            times gen-timestamp-pair]
    {:stage-name name :status status
     :started-at (:started-at times) :completed-at (:completed-at times)}))

(def gen-stage-records
  "Vector of stage records with unique names."
  (gen/fmap
    (fn [stages]
      (vec (vals (reduce (fn [m s] (assoc m (:stage-name s) s)) {} stages))))
    (gen/vector gen-stage-record 0 5)))

(def gen-artifact-record
  "An artifact record with filename and size."
  (gen/let [name (gen/not-empty gen/string-alphanumeric)
            ext (gen/elements [".jar" ".zip" ".tar.gz" ".json" ".txt"])
            size (gen/choose 100 1000000)]
    {:filename (str name ext) :size-bytes size}))

(def gen-artifact-records
  "Vector of artifact records with unique filenames."
  (gen/fmap
    (fn [artifacts]
      (vec (vals (reduce (fn [m a] (assoc m (:filename a) a)) {} artifacts))))
    (gen/vector gen-artifact-record 0 5)))

;; ---------------------------------------------------------------------------
;; Monorepo / Branch Overrides
;; ---------------------------------------------------------------------------

(def gen-path-segment
  "A path segment (no slashes)."
  (gen/not-empty gen/string-alphanumeric))

(def gen-file-path-with-dirs
  "A file path like src/main/core.clj."
  (gen/fmap
    (fn [[dirs file ext]]
      (str (clojure.string/join "/" dirs) "/" file ext))
    (gen/tuple
      (gen/vector gen-path-segment 1 3)
      gen-path-segment
      (gen/elements [".clj" ".java" ".py" ".md" ".txt" ".json"]))))

(def gen-branch-name
  "A branch name like main, feature/foo, release/1.0."
  (gen/one-of
    [(gen/elements ["main" "master" "develop"])
     (gen/fmap #(str "feature/" %) gen-path-segment)
     (gen/fmap #(str "release/" %) gen-path-segment)
     (gen/fmap #(str "hotfix/" %) gen-path-segment)]))

(def gen-pipeline-def
  "A minimal pipeline definition for override testing."
  (gen/let [n (gen/choose 1 4)
            params (gen/map gen/keyword (gen/not-empty gen/string-alphanumeric) {:max-elements 3})
            env (gen/map (gen/not-empty gen/string-alphanumeric) (gen/not-empty gen/string-alphanumeric) {:max-elements 3})]
    {:stages (mapv (fn [i] {:stage-name (str "stage-" i)
                            :steps [{:type :shell :command (str "echo " i)}]})
                   (range n))
     :parameters params
     :environment env}))

;; ---------------------------------------------------------------------------
;; Util / Config
;; ---------------------------------------------------------------------------

(def gen-edn-value
  "Primitive EDN values safe for serialize/deserialize round-trip."
  (gen/one-of
    [gen/string-alphanumeric
     gen/nat
     gen/boolean
     gen/keyword
     (gen/return nil)
     (gen/vector gen/nat 0 3)
     (gen/map gen/keyword gen/nat {:max-elements 3})]))

(def gen-nested-map
  "Nested maps for deep-merge testing."
  (gen/let [k1 gen/keyword
            k2 gen/keyword
            v1 gen/nat
            v2 gen/nat
            k3 gen/keyword
            inner (gen/map gen/keyword gen/nat {:max-elements 2})]
    {k1 v1 k2 {k3 v2} :nested inner}))

(def gen-env-var-value
  "Environment variable value strings for coerce testing."
  (gen/one-of
    [(gen/return "true")
     (gen/return "false")
     (gen/fmap str (gen/choose 0 99999))
     (gen/fmap #(str ":" %) (gen/not-empty gen/string-alphanumeric))
     (gen/not-empty gen/string-alphanumeric)]))

;; ---------------------------------------------------------------------------
;; IaC State
;; ---------------------------------------------------------------------------

(def gen-terraform-state-json
  "Terraform state JSON with resources array."
  (gen/fmap
    (fn [resources]
      (json/write-str
        {:version 4
         :terraform_version "1.5.0"
         :resources
         (mapv (fn [[rt nm]]
                 {:type rt :name nm
                  :instances [{:attributes {:id (str nm "-id")}}]})
               resources)}))
    (gen/vector
      (gen/tuple gen-terraform-resource-type gen-path-segment)
      0 5)))

;; ---------------------------------------------------------------------------
;; Notify
;; ---------------------------------------------------------------------------

(def gen-build-status-keyword
  "Build status as keyword."
  (gen/elements [:success :failure :aborted]))

(def gen-build-notification
  "Build notification map for notify testing."
  (gen/let [job-id (gen/not-empty gen/string-alphanumeric)
            build-number (gen/choose 1 9999)
            status gen-build-status-keyword
            duration-ms (gen/choose 1000 600000)
            stage-count (gen/choose 1 10)]
    {:job-id job-id
     :build-number build-number
     :build-status status
     :build-id (str "build-" build-number)
     :duration-ms duration-ms
     :stage-count stage-count}))

;; ---------------------------------------------------------------------------
;; Log Masker
;; ---------------------------------------------------------------------------

(def gen-secret-values
  "A set of non-empty secret strings for masking tests."
  (gen/fmap set
    (gen/vector (gen/not-empty gen/string-alphanumeric) 1 5)))

;; ---------------------------------------------------------------------------
;; Wave 4: IaC Command Builders
;; ---------------------------------------------------------------------------

(def gen-shell-quotable-string
  "Strings with special chars that test shell quoting robustness."
  (gen/one-of
    [gen/string-alphanumeric
     (gen/return "'")
     (gen/return "a'b")
     (gen/return "it's a test")
     (gen/return "foo bar")
     (gen/return "x\"y")
     (gen/return "a$b")
     (gen/return "")
     gen/string]))

(def gen-var-files
  "Vector of file paths for terraform -var-file arguments."
  (gen/vector
    (gen/fmap #(str % ".tfvars") (gen/not-empty gen/string-alphanumeric))
    0 3))

(def gen-vars-map
  "Map of terraform variable key-value pairs."
  (gen/map gen/keyword (gen/not-empty gen/string-alphanumeric) {:max-elements 3}))

;; ---------------------------------------------------------------------------
;; Wave 4: Docker
;; ---------------------------------------------------------------------------

(def gen-docker-image-name
  "Valid Docker image names matching ^[a-zA-Z0-9][a-zA-Z0-9._\\-/:@]*$."
  (gen/one-of
    [(gen/elements ["alpine" "ubuntu" "node" "python" "openjdk"])
     (gen/fmap (fn [[base tag]] (str base ":" tag))
       (gen/tuple
         (gen/elements ["alpine" "node" "python" "ubuntu" "nginx"])
         (gen/elements ["latest" "18" "3.12" "22.04" "slim"])))
     (gen/fmap (fn [[reg repo tag]] (str reg "/" repo ":" tag))
       (gen/tuple
         (gen/elements ["gcr.io" "docker.io" "registry.example.com"])
         (gen/elements ["myapp" "worker" "api"])
         (gen/elements ["v1.0" "latest" "sha256"])))]))

(def gen-invalid-docker-image
  "Invalid Docker image names that should fail validation."
  (gen/one-of
    [(gen/return "")
     (gen/return " ")
     (gen/fmap #(str "a" % "b") (gen/elements [" " ";" "|" "&" "(" ")"]))
     (gen/fmap (fn [_] (apply str "a" (repeat 256 "x"))) gen/nat)]))

(def gen-docker-name
  "Valid Docker names matching ^[a-zA-Z0-9][a-zA-Z0-9._\\-]*$ (≤128 chars)."
  (gen/fmap (fn [[first-char rest-chars]]
              (apply str first-char rest-chars))
    (gen/tuple
      (gen/elements (vec "abcdefghijklmnopqrstuvwxyz0123456789"))
      (gen/vector
        (gen/elements (vec "abcdefghijklmnopqrstuvwxyz0123456789._-"))
        0 20))))

(def gen-invalid-docker-name
  "Invalid Docker names."
  (gen/one-of
    [(gen/return "")
     (gen/return " ")
     (gen/fmap #(str "." %) gen/string-alphanumeric)
     (gen/fmap #(str "a" % "b") (gen/elements [" " ";" "|" "&"]))]))

(def gen-volume-spec
  "Volume mount spec like '/host:/container'."
  (gen/fmap (fn [[host container]]
              (str "/" host ":/" container))
    (gen/tuple (gen/not-empty gen/string-alphanumeric)
               (gen/not-empty gen/string-alphanumeric))))

(def gen-volume-spec-with-token
  "Volume spec with ${WORKSPACE} token for resolution testing."
  (gen/fmap (fn [container]
              (str "${WORKSPACE}:/" container))
    (gen/not-empty gen/string-alphanumeric)))

(def gen-cache-volume-name
  "Valid Docker named volume names matching ^[a-zA-Z0-9][a-zA-Z0-9_\\-]*$."
  (gen/fmap (fn [[first-char rest-chars]]
              (apply str first-char rest-chars))
    (gen/tuple
      (gen/elements (vec "abcdefghijklmnopqrstuvwxyz0123456789"))
      (gen/vector
        (gen/elements (vec "abcdefghijklmnopqrstuvwxyz0123456789_-"))
        0 15))))

(def gen-valid-mount-path
  "Valid container mount paths matching ^/[a-zA-Z0-9._/\\-]+$."
  (gen/fmap (fn [parts]
              (str "/" (clojure.string/join "/" parts)))
    (gen/vector (gen/not-empty gen/string-alphanumeric) 1 3)))

(def gen-invalid-mount-path
  "Invalid container mount paths."
  (gen/one-of
    [(gen/return "")
     (gen/return " ")
     (gen/return "relative/path")
     (gen/return "/bad/../traversal")
     (gen/return "/path with spaces")]))

(def gen-env-map
  "Map of environment variables {\"KEY\" \"value\"}."
  (gen/map (gen/not-empty gen/string-alphanumeric)
           (gen/not-empty gen/string-alphanumeric)
           {:max-elements 4}))

;; ---------------------------------------------------------------------------
;; Wave 4: Policy
;; ---------------------------------------------------------------------------

(def gen-glob-pattern-simple
  "Simple glob patterns with * and ? wildcards."
  (gen/one-of
    [(gen/not-empty gen/string-alphanumeric)  ;; literal
     (gen/return "*")
     (gen/fmap #(str % "*") (gen/not-empty gen/string-alphanumeric))
     (gen/fmap #(str "*" %) (gen/not-empty gen/string-alphanumeric))
     (gen/fmap #(str % "/*") (gen/not-empty gen/string-alphanumeric))]))

(def gen-policy-operator
  "Policy parameter restriction operators."
  (gen/elements [:equals :not-equals :contains :exists :not-exists]))

(def gen-approval-override
  "Approval override spec with min-approvals and approver-group."
  (gen/let [min-approvals (gen/choose 1 5)
            approvers (gen/vector (gen/not-empty gen/string-alphanumeric) 1 3)]
    {:min-approvals min-approvals
     :approver-group approvers}))

;; ---------------------------------------------------------------------------
;; Wave 4: Expressions
;; ---------------------------------------------------------------------------

(def gen-expression-type
  "Expression type keywords."
  (gen/elements [:parameters :secrets :env]))

(def gen-expression-name
  "Valid expression names (alphanumeric with hyphens)."
  (gen/not-empty gen/string-alphanumeric))

(def gen-expression-string
  "String containing ${{ namespace.name }} expression."
  (gen/let [ns-str (gen/elements ["parameters" "secrets" "env"])
            name gen-expression-name]
    (str "${{ " ns-str "." name " }}")))

(def gen-expression-context
  "Context map for expression resolution."
  (gen/let [params (gen/map gen/keyword (gen/not-empty gen/string-alphanumeric) {:max-elements 3})
            secrets (gen/map (gen/not-empty gen/string-alphanumeric) (gen/not-empty gen/string-alphanumeric) {:max-elements 2})
            env (gen/map (gen/not-empty gen/string-alphanumeric) (gen/not-empty gen/string-alphanumeric) {:max-elements 2})]
    {:parameters params :secrets secrets :env env}))

;; ---------------------------------------------------------------------------
;; Wave 4: YAML Conversion
;; ---------------------------------------------------------------------------

(def gen-yaml-step
  "A YAML step map for conversion testing."
  (gen/let [name (gen/not-empty gen/string-alphanumeric)
            cmd (gen/not-empty gen/string-alphanumeric)
            has-image? gen/boolean
            image (gen/elements ["alpine" "node:18" "python:3"])]
    (cond-> {:name name :run cmd}
      has-image? (assoc :image image))))

(def gen-yaml-stage
  "A YAML stage map for conversion testing."
  (gen/let [name (gen/not-empty gen/string-alphanumeric)
            steps (gen/vector gen-yaml-step 1 3)
            parallel? gen/boolean]
    {:name name :steps steps :parallel parallel?}))

(def gen-yaml-condition
  "A YAML when condition: branch or param type."
  (gen/one-of
    [(gen/fmap (fn [b] {:branch b}) (gen/not-empty gen/string-alphanumeric))
     (gen/let [p (gen/not-empty gen/string-alphanumeric)
               v (gen/not-empty gen/string-alphanumeric)]
       {:param p :value v})]))

(def gen-yaml-parameters
  "YAML parameter definitions map for conversion testing."
  (gen/map gen/keyword
    (gen/let [param-type (gen/elements ["text" "choice"])
              choices (gen/vector (gen/not-empty gen/string-alphanumeric) 2 4)
              default-val (gen/not-empty gen/string-alphanumeric)]
      (cond-> {:type param-type}
        (= param-type "choice") (assoc :choices choices)
        true (assoc :default default-val)))
    {:min-elements 1 :max-elements 3}))

;; ---------------------------------------------------------------------------
;; Wave 4: Template Merging
;; ---------------------------------------------------------------------------

(def gen-stage-def
  "A stage definition for template merge testing."
  (gen/let [name (gen/not-empty gen/string-alphanumeric)
            cmd (gen/not-empty gen/string-alphanumeric)]
    {:stage-name name
     :steps [{:type :shell :command cmd}]}))

(def gen-stage-defs
  "Vector of stage definitions with unique names."
  (gen/fmap
    (fn [stages]
      (vec (vals (reduce (fn [m s] (assoc m (:stage-name s) s)) {} stages))))
    (gen/vector gen-stage-def 1 4)))

(def gen-pipeline-for-merge
  "A pipeline map for merge testing."
  (gen/let [stages gen-stage-defs
            env (gen/map (gen/not-empty gen/string-alphanumeric)
                         (gen/not-empty gen/string-alphanumeric)
                         {:max-elements 3})
            artifacts (gen/vector (gen/not-empty gen/string-alphanumeric) 0 3)]
    {:stages stages
     :env env
     :artifacts artifacts}))

;; ---------------------------------------------------------------------------
;; Wave 4: Test Parser
;; ---------------------------------------------------------------------------

(def gen-junit-testcase-xml
  "A JUnit <testcase> XML element string."
  (gen/let [name (gen/not-empty gen/string-alphanumeric)
            classname (gen/not-empty gen/string-alphanumeric)
            time-val (gen/double* {:min 0.001 :max 10.0 :NaN? false :infinite? false})
            status (gen/elements [:pass :fail :error :skip])]
    (case status
      :pass (str "<testcase name=\"" name "\" classname=\"" classname
                 "\" time=\"" (format "%.3f" time-val) "\"/>")
      :fail (str "<testcase name=\"" name "\" classname=\"" classname
                 "\" time=\"" (format "%.3f" time-val) "\">"
                 "<failure message=\"assertion failed\">expected X got Y</failure></testcase>")
      :error (str "<testcase name=\"" name "\" classname=\"" classname
                  "\" time=\"" (format "%.3f" time-val) "\">"
                  "<error message=\"NPE\">stack trace</error></testcase>")
      :skip (str "<testcase name=\"" name "\" classname=\"" classname
                 "\" time=\"" (format "%.3f" time-val) "\">"
                 "<skipped/></testcase>"))))

(def gen-junit-xml
  "A valid JUnit XML testsuite string."
  (gen/fmap (fn [testcases]
              (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                   "<testsuite name=\"suite\" tests=\"" (count testcases) "\">"
                   (apply str testcases)
                   "</testsuite>"))
    (gen/vector gen-junit-testcase-xml 1 5)))

(def gen-tap-line
  "A TAP output line (ok or not ok)."
  (gen/let [num (gen/choose 1 100)
            name (gen/not-empty gen/string-alphanumeric)
            pass? gen/boolean]
    (if pass?
      (str "ok " num " - " name)
      (str "not ok " num " - " name))))

(def gen-tap-output
  "A valid TAP output string with header and test lines."
  (gen/let [lines (gen/vector gen-tap-line 1 5)]
    (str "TAP version 13\n1.." (count lines) "\n"
         (clojure.string/join "\n" lines))))

(def gen-generic-test-output
  "String matching generic test output patterns ('X passed, Y failed')."
  (gen/let [passed (gen/choose 0 100)
            failed (gen/choose 0 20)]
    (str passed " passed, " failed " failed")))

;; ---------------------------------------------------------------------------
;; Wave 4: Stage Cache & Compliance
;; ---------------------------------------------------------------------------

(def gen-stage-def-for-fingerprint
  "Stage definition with commands for fingerprint testing."
  (gen/let [name (gen/not-empty gen/string-alphanumeric)
            cmds (gen/vector (gen/not-empty gen/string-alphanumeric) 1 3)]
    {:stage-name name
     :steps (mapv (fn [c] {:command c}) cmds)}))

(def gen-audit-entry
  "An audit log entry for compliance testing."
  (gen/let [id gen-id
            user-id gen-id
            username (gen/not-empty gen/string-alphanumeric)
            action (gen/elements ["login" "logout" "trigger-build" "cancel-build"
                                  "create-job" "delete-job" "manage-user"
                                  "approve-gate" "reject-gate"])
            resource-type (gen/elements ["build" "job" "user" "pipeline"])
            resource-id gen-id]
    {:id id
     :user-id user-id
     :username username
     :action action
     :resource-type resource-type
     :resource-id resource-id
     :detail ""
     :ip-address "127.0.0.1"
     :user-agent "test-agent"}))
