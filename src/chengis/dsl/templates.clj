(ns chengis.dsl.templates
  "Pipeline template resolution — resolves `extends` declarations by loading
   base templates from the database and merging with the extending pipeline.

   Merge rules:
   - Stages: pipeline stages override template stages by matching :stage-name.
     Pipeline stages not in template are appended. Template-only stages are kept.
   - Top-level keys (:env, :container, :post-actions): pipeline wins on conflict.
   - Max resolution depth: 3 (templates can extend templates)."
  (:require [chengis.db.template-store :as template-store]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Stage merging
;; ---------------------------------------------------------------------------

(defn- merge-stages
  "Merge template stages with pipeline stages.
   Pipeline stages override template stages by name (stage-name match).
   Template-only stages are kept. Pipeline-only stages are appended."
  [template-stages pipeline-stages]
  (if (empty? pipeline-stages)
    template-stages
    (if (empty? template-stages)
      pipeline-stages
      (let [;; Index pipeline stages by name for fast lookup
            pipeline-by-name (into {} (map (fn [s] [(:stage-name s) s]) pipeline-stages))
            pipeline-names (set (map :stage-name pipeline-stages))
            template-names (set (map :stage-name template-stages))
            ;; Walk template stages: replace with pipeline version if present
            merged (mapv (fn [ts]
                           (if-let [ps (get pipeline-by-name (:stage-name ts))]
                             ps  ;; Pipeline overrides template stage
                             ts))
                         template-stages)
            ;; Append pipeline stages that aren't in template
            new-stages (filterv (fn [ps] (not (contains? template-names (:stage-name ps))))
                                pipeline-stages)]
        (into merged new-stages)))))

;; ---------------------------------------------------------------------------
;; Pipeline merging
;; ---------------------------------------------------------------------------

(defn- merge-pipeline
  "Deep-merge a base (template) pipeline with an extending pipeline.
   Pipeline values take precedence over template values."
  [base extension]
  (let [merged-stages (merge-stages (:stages base) (:stages extension))]
    (-> base
        ;; Start from base, override with extension values
        (merge (dissoc extension :stages :extends))
        ;; Use merged stages
        (assoc :stages merged-stages)
        ;; Merge env maps (extension wins)
        (cond->
          (or (:env base) (:env extension))
          (assoc :env (merge (:env base) (:env extension)))

          ;; Merge post-actions (extension wins per group)
          (or (:post-actions base) (:post-actions extension))
          (assoc :post-actions (merge (:post-actions base) (:post-actions extension)))

          ;; Merge artifacts (union)
          (or (:artifacts base) (:artifacts extension))
          (assoc :artifacts (vec (distinct (concat (:artifacts base) (:artifacts extension)))))

          ;; Merge notify (union)
          (or (:notify base) (:notify extension))
          (assoc :notify (vec (concat (:notify base) (:notify extension))))))))

;; ---------------------------------------------------------------------------
;; Template loading
;; ---------------------------------------------------------------------------

(defn- load-template-content
  "Load and parse template content from database."
  [ds template-name]
  (when-let [template (template-store/get-template-by-name ds template-name)]
    (try
      (let [content (:content template)
            format (:format template)]
        (case format
          "edn" (edn/read-string {:readers {}} content)
          ;; For YAML templates, we'd need to parse YAML and convert
          ;; For now, only EDN templates are supported
          (do (log/warn "Unsupported template format:" format)
              nil)))
      (catch Exception e
        (log/warn "Failed to parse template" template-name ":" (.getMessage e))
        nil))))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(defn resolve-extends
  "Resolve template inheritance for a pipeline data map.
   If the pipeline has an :extends key, load the template and merge.
   Supports chained inheritance up to max-depth (default 3).

   Arguments:
     ds       - datasource for template lookups
     pipeline - pipeline data map (may contain :extends \"template-name\")
     opts     - optional {:max-depth 3}

   Returns the resolved pipeline map (without :extends key)."
  [ds pipeline & [{:keys [max-depth] :or {max-depth 3}}]]
  (if-not (:extends pipeline)
    pipeline
    (loop [current pipeline
           depth 0
           seen #{}]
      (let [template-name (:extends current)]
        (cond
          ;; No more extends — done
          (nil? template-name)
          (dissoc current :extends)

          ;; Depth limit
          (>= depth max-depth)
          (do (log/warn "Template resolution depth exceeded:" depth "at" template-name)
              (dissoc current :extends))

          ;; Cycle detection
          (contains? seen template-name)
          (do (log/warn "Template cycle detected:" template-name "in" seen)
              (dissoc current :extends))

          ;; Load and merge
          :else
          (let [base (load-template-content ds template-name)]
            (if-not base
              (do (log/warn "Template not found:" template-name)
                  (dissoc current :extends))
              (let [merged (merge-pipeline base (dissoc current :extends))]
                (if (:extends base)
                  ;; Base template also extends something — continue chain
                  (recur (assoc merged :extends (:extends base))
                         (inc depth)
                         (conj seen template-name))
                  (dissoc merged :extends))))))))))
