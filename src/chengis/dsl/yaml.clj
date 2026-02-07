(ns chengis.dsl.yaml
  "GitHub Actions-style YAML pipeline parser.
   Parses YAML workflow files into the internal pipeline data format.

   Supported file locations:
     .chengis/workflow.yml
     chengis.yml

   YAML Format:
     name: my-app
     description: 'Build and test'
     container:
       image: node:18
     env:
       CI: 'true'
     parameters:
       environment:
         type: choice
         choices: [staging, production]
         default: staging
     on:
       push:
         branches: [main]
     stages:
       - name: Build
         steps:
           - name: Install
             run: npm install
     post:
       always:
         - name: Cleanup
           run: rm -rf .cache
     artifacts:
       - 'dist/**'
     notify:
       - type: slack
         webhook-url: ${{ secrets.SLACK_WEBHOOK }}"
  (:require [chengis.dsl.expressions :as expr]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; File detection
;; ---------------------------------------------------------------------------

(def ^:private yaml-file-candidates
  "Possible YAML pipeline file locations, in priority order."
  [".chengis/workflow.yml"
   ".chengis/workflow.yaml"
   "chengis.yml"
   "chengis.yaml"])

(defn detect-yaml-file
  "Check if a YAML workflow file exists in the workspace.
   Returns the file path if found, nil otherwise."
  [workspace-dir]
  (some (fn [candidate]
          (let [f (io/file workspace-dir candidate)]
            (when (.exists f)
              (.getAbsolutePath f))))
        yaml-file-candidates))

;; ---------------------------------------------------------------------------
;; Conversion: YAML → internal pipeline format
;; ---------------------------------------------------------------------------

(defn- convert-yaml-condition
  "Convert a YAML 'when' clause to internal condition format.
   {:branch: main} → {:type :branch :value \"main\"}
   {:param: env, :value: prod} → {:type :param :param \"env\" :value \"prod\"}"
  [when-clause]
  (when when-clause
    (cond
      (:branch when-clause)
      {:type :branch :value (str (:branch when-clause))}

      (:param when-clause)
      {:type :param :param (str (:param when-clause))
       :value (str (:value when-clause))}

      :else nil)))

(defn- convert-yaml-step
  "Convert a YAML step map to internal step format."
  [yaml-step]
  (let [has-image? (some? (or (:image yaml-step)
                               (get-in yaml-step [:container :image])))]
    (cond-> {:step-name (str (:name yaml-step))
             :type      (if has-image? :docker :shell)
             :command   (str (:run yaml-step))}
      has-image?
      (assoc :image (or (:image yaml-step)
                        (get-in yaml-step [:container :image])))
      (:env yaml-step)
      (assoc :env (reduce-kv (fn [m k v] (assoc m (name k) (str v)))
                             {} (:env yaml-step)))
      (:timeout yaml-step)
      (assoc :timeout (:timeout yaml-step))
      (get-in yaml-step [:container :volumes])
      (assoc :volumes (vec (get-in yaml-step [:container :volumes])))
      (get-in yaml-step [:container :workdir])
      (assoc :workdir (get-in yaml-step [:container :workdir]))
      (get-in yaml-step [:container :network])
      (assoc :network (get-in yaml-step [:container :network])))))

(defn- convert-yaml-stage
  "Convert a YAML stage map to internal stage format."
  [yaml-stage]
  (cond-> {:stage-name (str (:name yaml-stage))
           :parallel?  (boolean (:parallel yaml-stage))
           :steps      (mapv convert-yaml-step (:steps yaml-stage))}
    (:when yaml-stage)
    (assoc :condition (convert-yaml-condition (:when yaml-stage)))
    (:container yaml-stage)
    (assoc :container (:container yaml-stage))))

(defn- convert-yaml-post-steps
  "Convert a vector of YAML post-action steps."
  [steps]
  (when (seq steps)
    (mapv convert-yaml-step steps)))

(defn- convert-yaml-parameters
  "Convert YAML parameter definitions to internal format.
   YAML:  {environment: {type: choice, choices: [a, b], default: a}}
   → [{:name \"environment\" :type :choice :choices [\"a\" \"b\"] :default \"a\"}]"
  [params]
  (when (map? params)
    (mapv (fn [[k v]]
            (cond-> {:name (name k)
                     :type (keyword (or (:type v) "text"))}
              (:choices v)  (assoc :choices (mapv str (:choices v)))
              (:default v)  (assoc :default (str (:default v)))
              (:description v) (assoc :description (:description v))))
          params)))

(defn- convert-yaml-triggers
  "Convert YAML 'on' trigger config to internal format."
  [on-config]
  (when (map? on-config)
    (cond-> {}
      (:push on-config)
      (assoc :push (cond-> {}
                     (get-in on-config [:push :branches])
                     (assoc :branches (vec (get-in on-config [:push :branches])))))
      (:schedule on-config)
      (assoc :schedule (mapv (fn [s] {:interval (:interval s)}) (:schedule on-config))))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-yaml-workflow
  "Validate a parsed YAML workflow map.
   Returns {:valid? bool :errors [\"error messages\"]}."
  [data]
  (let [errors (atom [])]
    (when-not (map? data)
      (swap! errors conj "Workflow must be a map"))

    (when (map? data)
      ;; stages is required
      (when-not (:stages data)
        (swap! errors conj "Missing required key 'stages'"))

      (when (:stages data)
        (when-not (sequential? (:stages data))
          (swap! errors conj "'stages' must be a list"))

        (when (and (sequential? (:stages data)) (empty? (:stages data)))
          (swap! errors conj "'stages' must not be empty"))

        (when (and (sequential? (:stages data)) (seq (:stages data)))
          (doseq [[idx stage] (map-indexed vector (:stages data))]
            (let [prefix (str "Stage " (inc idx))]
              (when-not (map? stage)
                (swap! errors conj (str prefix ": must be a map")))

              (when (map? stage)
                (when (str/blank? (str (:name stage)))
                  (swap! errors conj (str prefix ": missing 'name'")))

                (when-not (:steps stage)
                  (swap! errors conj (str prefix " (" (or (:name stage) "?") "): missing 'steps'")))

                (when (:steps stage)
                  (when-not (sequential? (:steps stage))
                    (swap! errors conj (str prefix " (" (or (:name stage) "?") "): 'steps' must be a list")))

                  (when (and (sequential? (:steps stage)) (empty? (:steps stage)))
                    (swap! errors conj (str prefix " (" (or (:name stage) "?") "): 'steps' must not be empty")))

                  (when (and (sequential? (:steps stage)) (seq (:steps stage)))
                    (doseq [[sidx step] (map-indexed vector (:steps stage))]
                      (let [step-prefix (str prefix " (" (or (:name stage) "?") "), Step " (inc sidx))]
                        (when-not (map? step)
                          (swap! errors conj (str step-prefix ": must be a map")))

                        (when (map? step)
                          (when (str/blank? (str (:name step)))
                            (swap! errors conj (str step-prefix ": missing 'name'")))

                          (when (str/blank? (str (:run step)))
                            (swap! errors conj (str step-prefix " (" (or (:name step) "?") "): missing 'run'"))))))))

                ;; Validate container if present
                (when (:container stage)
                  (when-not (map? (:container stage))
                    (swap! errors conj (str prefix " (" (or (:name stage) "?") "): 'container' must be a map")))
                  (when (and (map? (:container stage))
                             (str/blank? (str (get-in stage [:container :image]))))
                    (swap! errors conj (str prefix " (" (or (:name stage) "?") "): container missing 'image'"))))))))

        ;; Validate top-level container if present
        (when (:container data)
          (when-not (map? (:container data))
            (swap! errors conj "Top-level 'container' must be a map"))
          (when (and (map? (:container data))
                     (str/blank? (str (get-in data [:container :image]))))
            (swap! errors conj "Top-level container missing 'image'")))))

    {:valid? (empty? @errors)
     :errors @errors}))

;; ---------------------------------------------------------------------------
;; Main entry point
;; ---------------------------------------------------------------------------

(defn parse-yaml-workflow
  "Parse a YAML workflow file from disk. Returns:
   {:pipeline {...}} on success
   {:error \"message\"} on failure

   File size limited to 1MB for safety."
  [file-path]
  (try
    (let [f (io/file file-path)]
      (when-not (.exists f)
        (throw (ex-info "File not found" {:path file-path})))

      ;; Size check
      (when (> (.length f) (* 1024 1024))
        (throw (ex-info "YAML workflow exceeds 1MB size limit" {:size (.length f)})))

      (let [content (slurp f)
            data (yaml/parse-string content)]

        ;; Validate
        (let [{:keys [valid? errors]} (validate-yaml-workflow data)]
          (if-not valid?
            {:error (str "Validation failed: " (str/join "; " errors))}
            ;; Convert to internal format
            (let [stages (mapv convert-yaml-stage (:stages data))
                  post-actions (when-let [post (:post data)]
                                 (cond-> {}
                                   (:always post)
                                   (assoc :always (convert-yaml-post-steps (:always post)))
                                   (:on-success post)
                                   (assoc :on-success (convert-yaml-post-steps (or (:on-success post)
                                                                                    (get post :on_success))))
                                   (:on-failure post)
                                   (assoc :on-failure (convert-yaml-post-steps (or (:on-failure post)
                                                                                    (get post :on_failure))))))
                  artifact-patterns (when-let [arts (:artifacts data)]
                                     (vec (map str arts)))
                  notify-configs (when-let [notifs (:notify data)]
                                   (vec (map (fn [n]
                                               (let [base (reduce-kv (fn [m k v]
                                                                       (assoc m (keyword k) v))
                                                                     {} n)]
                                                 ;; Ensure :type is a keyword
                                                 (cond-> base
                                                   (string? (:type base))
                                                   (update :type keyword))))
                                             notifs)))
                  env-map (when (:env data)
                            (reduce-kv (fn [m k v] (assoc m (name k) (str v)))
                                       {} (:env data)))
                  parameters (convert-yaml-parameters (:parameters data))
                  triggers (convert-yaml-triggers (:on data))
                  pipeline (cond-> {:stages stages
                                    :pipeline-source "yaml"}
                             (:name data)          (assoc :pipeline-name (str (:name data)))
                             (:description data)   (assoc :description (str (:description data)))
                             (:container data)     (assoc :container (:container data))
                             (seq env-map)          (assoc :env env-map)
                             (seq post-actions)     (assoc :post-actions post-actions)
                             (seq artifact-patterns)(assoc :artifacts artifact-patterns)
                             (seq notify-configs)   (assoc :notify notify-configs)
                             (seq parameters)       (assoc :parameters parameters)
                             triggers               (assoc :triggers triggers))]
              (log/info "YAML workflow parsed successfully:"
                        (count stages) "stages from" file-path)
              {:pipeline pipeline})))))
    (catch Exception e
      {:error (str "Failed to parse YAML workflow: " (.getMessage e))})))

(defn convert-yaml-to-pipeline
  "Parse a YAML string (not file) into a pipeline map.
   Useful for testing and programmatic usage."
  [yaml-string]
  (try
    (let [data (yaml/parse-string yaml-string)
          {:keys [valid? errors]} (validate-yaml-workflow data)]
      (if-not valid?
        {:error (str "Validation failed: " (str/join "; " errors))}
        (let [stages (mapv convert-yaml-stage (:stages data))
              pipeline (cond-> {:stages stages}
                         (:name data)        (assoc :pipeline-name (str (:name data)))
                         (:description data) (assoc :description (str (:description data)))
                         (:container data)   (assoc :container (:container data)))]
          {:pipeline pipeline})))
    (catch Exception e
      {:error (str "Failed to parse YAML: " (.getMessage e))})))
