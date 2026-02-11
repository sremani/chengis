(ns chengis.engine.iac-cost
  "IaC cost estimation — resource-based cost lookup for Terraform, Pulumi,
   and CloudFormation. Uses a built-in lookup table of approximate monthly
   costs for common cloud resources as a fallback when no external cost
   estimation tool (e.g., Infracost) is available."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Built-in resource cost table
;; ---------------------------------------------------------------------------

(def basic-resource-costs
  "Approximate monthly costs for common cloud resources (USD).
   Used as fallback when no external cost tool is available."
  {"aws_instance"                {:monthly 30.0  :description "EC2 instance (t3.medium)"}
   "aws_s3_bucket"               {:monthly 2.3   :description "S3 bucket"}
   "aws_rds_instance"            {:monthly 50.0  :description "RDS instance (db.t3.medium)"}
   "aws_lambda_function"         {:monthly 0.0   :description "Lambda (pay-per-use)"}
   "aws_dynamodb_table"          {:monthly 25.0  :description "DynamoDB table"}
   "aws_ecs_service"             {:monthly 40.0  :description "ECS Fargate service"}
   "aws_elasticache_cluster"     {:monthly 45.0  :description "ElastiCache (cache.t3.medium)"}
   "aws_lb"                      {:monthly 22.0  :description "Application Load Balancer"}
   "aws_cloudfront_distribution" {:monthly 5.0   :description "CloudFront distribution"}
   "aws_sqs_queue"               {:monthly 0.5   :description "SQS queue"}
   "aws_sns_topic"               {:monthly 0.5   :description "SNS topic"}
   "aws_vpc"                     {:monthly 0.0   :description "VPC (no charge)"}
   "aws_subnet"                  {:monthly 0.0   :description "Subnet (no charge)"}
   "aws_security_group"          {:monthly 0.0   :description "Security Group (no charge)"}
   "aws_iam_role"                {:monthly 0.0   :description "IAM role (no charge)"}
   "google_compute_instance"     {:monthly 25.0  :description "GCE instance (e2-medium)"}
   "google_storage_bucket"       {:monthly 2.0   :description "GCS bucket"}
   "google_sql_database_instance" {:monthly 45.0 :description "Cloud SQL instance"}
   "google_container_cluster"    {:monthly 72.0  :description "GKE cluster"}
   "google_cloud_run_service"    {:monthly 0.0   :description "Cloud Run (pay-per-use)"}
   "azurerm_virtual_machine"     {:monthly 35.0  :description "Azure VM (B2s)"}
   "azurerm_storage_account"     {:monthly 5.0   :description "Azure Storage Account"}
   "azurerm_sql_database"        {:monthly 50.0  :description "Azure SQL Database"}
   "azurerm_kubernetes_cluster"  {:monthly 70.0  :description "AKS cluster"}
   "azurerm_container_group"     {:monthly 30.0  :description "ACI container group"}
   "azurerm_app_service_plan"    {:monthly 55.0  :description "Azure App Service Plan"}
   "azurerm_cosmosdb_account"    {:monthly 25.0  :description "CosmosDB account"}
   "azurerm_redis_cache"         {:monthly 40.0  :description "Azure Redis Cache"}})

;; ---------------------------------------------------------------------------
;; Resource type normalization
;; ---------------------------------------------------------------------------

(defn- normalize-resource-type
  "Normalize resource type strings from different IaC tools to lookup keys.
   Pulumi uses 'aws:ec2:Instance' format -> 'aws_instance'.
   CloudFormation uses 'AWS::EC2::Instance' format -> 'aws_instance'."
  [resource-type tool-type]
  (case (when tool-type (name tool-type))
    "pulumi"
    ;; Pulumi format: 'aws:s3:Bucket' -> 'aws_s3_bucket'
    (let [parts (str/split (or resource-type "") #":")]
      (if (>= (count parts) 3)
        (str (str/lower-case (first parts)) "_"
             (str/lower-case (nth parts 2)))
        (str/lower-case (or resource-type ""))))

    "cloudformation"
    ;; CloudFormation format: 'AWS::S3::Bucket' -> 'aws_s3_bucket'
    (let [parts (str/split (or resource-type "") #"::")]
      (if (>= (count parts) 3)
        (str (str/lower-case (first parts)) "_"
             (-> (nth parts 2)
                 (str/replace #"([a-z])([A-Z])" "$1_$2")
                 str/lower-case))
        (str/lower-case (str/replace (or resource-type "") "::" "_"))))

    ;; Terraform already uses underscore format
    (str/lower-case (or resource-type ""))))

;; ---------------------------------------------------------------------------
;; Cost estimation
;; ---------------------------------------------------------------------------

(defn estimate-resource-cost
  "Look up estimated cost for a single resource.
   Only counts 'create' and 'update' actions — not 'no-op' or 'delete'.
   Returns {:resource-type str :monthly float :action str :description str}."
  [resource-type action]
  (let [rt (str/lower-case (or resource-type ""))
        lookup (get basic-resource-costs rt)
        monthly (if (and lookup (#{"create" "update" "replace"} action))
                  (:monthly lookup)
                  0.0)]
    {:resource-type rt
     :monthly monthly
     :action (or action "unknown")
     :description (or (:description lookup) "Unknown resource")}))

(defn estimate-plan-cost
  "Estimate total cost for a set of parsed resources.
   Takes the resources list from iac/parse-plan-summary.
   Returns {:total-monthly float :total-hourly float :currency str
            :resources [...per-resource costs...]}."
  [resources tool-type]
  (let [costed (mapv (fn [r]
                       (let [normalized (normalize-resource-type (:resource-type r) tool-type)
                             cost (estimate-resource-cost normalized (:action r))]
                         (merge r cost)))
                     (or resources []))
        total-monthly (reduce + 0.0 (map :monthly costed))
        total-hourly (/ total-monthly 730.0)] ;; ~730 hours per month
    {:total-monthly (double total-monthly)
     :total-hourly (double total-hourly)
     :currency "USD"
     :estimation-method "builtin"
     :resources costed}))

;; ---------------------------------------------------------------------------
;; Resource parsing — Terraform
;; ---------------------------------------------------------------------------

(defn parse-terraform-plan-resources
  "Parse terraform show -json output to extract resource changes.
   Returns [{:resource-type str :action str :name str} ...]."
  [plan-json-str]
  (try
    (let [parsed (json/read-str plan-json-str :key-fn keyword)
          changes (or (:resource_changes parsed) [])]
      (mapv (fn [rc]
              {:resource-type (or (:type rc) "")
               :name (or (:name rc) "")
               :action (let [actions (get-in rc [:change :actions])]
                         (cond
                           (= actions ["create"]) "create"
                           (= actions ["delete"]) "delete"
                           (= actions ["update"]) "update"
                           (= actions ["delete" "create"]) "replace"
                           (= actions ["create" "delete"]) "replace"
                           (= actions ["no-op"]) "no-op"
                           :else (str/join "," (or actions ["unknown"]))))})
            changes))
    (catch Exception e
      (log/warn "Failed to parse Terraform plan resources:" (.getMessage e))
      [])))

;; ---------------------------------------------------------------------------
;; Resource parsing — Pulumi
;; ---------------------------------------------------------------------------

(defn parse-pulumi-preview-resources
  "Parse pulumi preview --json output to extract resource changes.
   Returns [{:resource-type str :action str :name str} ...]."
  [preview-json-str]
  (try
    (let [parsed (json/read-str preview-json-str :key-fn keyword)
          steps (or (:steps parsed) [])]
      (mapv (fn [step]
              {:resource-type (or (:type step) "")
               :name (or (:name step)
                         (last (str/split (or (:urn step) "") #"::"))
                         "")
               :action (case (:op step)
                         "create" "create"
                         "delete" "delete"
                         "update" "update"
                         "replace" "replace"
                         "same" "no-op"
                         (or (:op step) "unknown"))})
            steps))
    (catch Exception e
      (log/warn "Failed to parse Pulumi preview resources:" (.getMessage e))
      [])))

;; ---------------------------------------------------------------------------
;; Resource parsing — CloudFormation
;; ---------------------------------------------------------------------------

(defn parse-cloudformation-changeset
  "Parse describe-change-set JSON output to extract resource changes.
   Returns [{:resource-type str :action str :name str} ...]."
  [changeset-json-str]
  (try
    (let [parsed (json/read-str changeset-json-str :key-fn keyword)
          changes (or (:Changes parsed) [])]
      (mapv (fn [change]
              (let [rc (:ResourceChange change)]
                {:resource-type (or (:ResourceType rc) "")
                 :name (or (:LogicalResourceId rc) "")
                 :action (case (:Action rc)
                           "Add" "create"
                           "Modify" "update"
                           "Remove" "delete"
                           "Import" "create"
                           (or (:Action rc) "unknown"))}))
            changes))
    (catch Exception e
      (log/warn "Failed to parse CloudFormation changeset resources:" (.getMessage e))
      [])))

;; ---------------------------------------------------------------------------
;; Cost summary formatting
;; ---------------------------------------------------------------------------

(defn format-cost-summary
  "Format a cost estimate as a human-readable summary string.
   Example: '$125.30/month ($0.17/hour) - 5 resources'."
  [estimate]
  (let [monthly (or (:total-monthly estimate) 0.0)
        hourly (or (:total-hourly estimate) 0.0)
        resource-count (count (or (:resources estimate) []))
        billable-count (count (filter #(pos? (:monthly %))
                                      (or (:resources estimate) [])))]
    (format "$%.2f/month ($%.2f/hour) - %d resource%s (%d billable)"
            monthly hourly
            resource-count (if (= 1 resource-count) "" "s")
            billable-count)))
