(ns chengis.dsl.chengisfile
  "Parse Chengisfile (EDN) from a repository into internal pipeline format.

   The Chengisfile is a pure-data EDN file that lives at the root of a
   source repository — the Pipeline as Code equivalent of a Jenkinsfile.

   Format:
     {:description \"My pipeline\"
      :stages [{:name \"Build\"
                :steps [{:name \"Compile\" :run \"mvn compile\"}]}
               {:name \"Test\"
                :parallel true
                :steps [{:name \"Unit\" :run \"mvn test\"}
                        {:name \"Lint\" :run \"mvn lint\"}]}
               {:name \"Deploy\"
                :when {:branch \"main\"}
                :steps [{:name \"Ship\" :run \"./deploy.sh\"
                         :env {\"ENV\" \"prod\"}}]}]}

   Security: Uses clojure.edn/read-string (no code execution).
   Tagged literals are disabled."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Path helpers
;; ---------------------------------------------------------------------------

(defn chengisfile-path
  "Return the expected Chengisfile path within a workspace directory."
  [workspace-dir]
  (str workspace-dir "/Chengisfile"))

(defn chengisfile-exists?
  "Check whether a Chengisfile exists in the given workspace."
  [workspace-dir]
  (.exists (io/file (chengisfile-path workspace-dir))))

;; ---------------------------------------------------------------------------
;; Conversion: Chengisfile EDN → internal pipeline format
;; ---------------------------------------------------------------------------

(defn convert-condition
  "Convert a Chengisfile :when clause to an internal condition map.
   {:branch \"main\"}  → {:type :branch :value \"main\"}
   {:param \"x\" :value \"y\"} → {:type :param :param \"x\" :value \"y\"}
   nil → nil"
  [when-clause]
  (when when-clause
    (cond
      (:branch when-clause)
      {:type :branch :value (:branch when-clause)}

      (:param when-clause)
      {:type :param :param (:param when-clause) :value (:value when-clause)}

      :else nil)))

(defn convert-step
  "Convert a Chengisfile step map to an internal step map.
   {:name \"Compile\" :run \"mvn compile\" :env {\"K\" \"V\"} :timeout 30000}
   → {:step-name \"Compile\" :type :shell :command \"mvn compile\" :env {\"K\" \"V\"} :timeout 30000}

   If the step has an :image key, it becomes a :docker type step."
  [edn-step]
  (let [is-docker? (some? (:image edn-step))]
    (cond-> {:step-name (:name edn-step)
             :type      (if is-docker? :docker :shell)
             :command   (:run edn-step)}
      is-docker?           (assoc :image (:image edn-step))
      (:env edn-step)      (assoc :env (:env edn-step))
      (:timeout edn-step)  (assoc :timeout (:timeout edn-step))
      (:dir edn-step)      (assoc :dir (:dir edn-step))
      (:volumes edn-step)  (assoc :volumes (:volumes edn-step))
      (:workdir edn-step)  (assoc :workdir (:workdir edn-step))
      (:network edn-step)  (assoc :network (:network edn-step)))))

(defn convert-stage
  "Convert a Chengisfile stage map to an internal stage map.
   {:name \"Build\" :parallel true :when {:branch \"main\"} :steps [...]}
   → {:stage-name \"Build\" :parallel? true :condition {...} :steps [...]}

   If the stage has a :container key, it is passed through for Docker wrapping."
  [edn-stage]
  (cond-> {:stage-name (:name edn-stage)
           :parallel?  (boolean (:parallel edn-stage))
           :steps      (mapv convert-step (:steps edn-stage))}
    (:when edn-stage)      (assoc :condition (convert-condition (:when edn-stage)))
    (:container edn-stage) (assoc :container (:container edn-stage))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-chengisfile
  "Validate a parsed Chengisfile EDN map.
   Returns {:valid? bool :errors [\"error messages\"]}."
  [data]
  (let [errors (atom [])]
    ;; Top-level checks
    (when-not (map? data)
      (swap! errors conj "Chengisfile must be a map"))

    (when (map? data)
      ;; :stages is required
      (when-not (:stages data)
        (swap! errors conj "Missing required key :stages"))

      (when (:stages data)
        (when-not (vector? (:stages data))
          (swap! errors conj ":stages must be a vector"))

        (when (and (vector? (:stages data)) (empty? (:stages data)))
          (swap! errors conj ":stages must not be empty"))

        (when (and (vector? (:stages data)) (seq (:stages data)))
          ;; Validate each stage
          (doseq [[idx stage] (map-indexed vector (:stages data))]
            (let [prefix (str "Stage " (inc idx))]
              (when-not (map? stage)
                (swap! errors conj (str prefix ": must be a map")))

              (when (map? stage)
                (when (str/blank? (:name stage))
                  (swap! errors conj (str prefix ": missing :name")))

                (when-not (:steps stage)
                  (swap! errors conj (str prefix " (" (or (:name stage) "?") "): missing :steps")))

                (when (:steps stage)
                  (when-not (vector? (:steps stage))
                    (swap! errors conj (str prefix " (" (or (:name stage) "?") "): :steps must be a vector")))

                  (when (and (vector? (:steps stage)) (empty? (:steps stage)))
                    (swap! errors conj (str prefix " (" (or (:name stage) "?") "): :steps must not be empty")))

                  (when (and (vector? (:steps stage)) (seq (:steps stage)))
                    (doseq [[sidx step] (map-indexed vector (:steps stage))]
                      (let [step-prefix (str prefix " (" (or (:name stage) "?") "), Step " (inc sidx))]
                        (when-not (map? step)
                          (swap! errors conj (str step-prefix ": must be a map")))

                        (when (map? step)
                          (when (str/blank? (:name step))
                            (swap! errors conj (str step-prefix ": missing :name")))

                          (when (str/blank? (:run step))
                            (swap! errors conj (str step-prefix " (" (or (:name step) "?") "): missing :run")))

                          (when (and (:env step) (not (map? (:env step))))
                            (swap! errors conj (str step-prefix " (" (or (:name step) "?") "): :env must be a map")))

                          (when (and (:timeout step) (not (pos-int? (:timeout step))))
                            (swap! errors conj (str step-prefix " (" (or (:name step) "?") "): :timeout must be a positive integer")))))))

                ;; Validate :when clause if present
                (when (:when stage)
                  (let [w (:when stage)]
                    (when-not (map? w)
                      (swap! errors conj (str prefix " (" (or (:name stage) "?") "): :when must be a map")))
                    (when (map? w)
                      (when-not (or (:branch w) (:param w))
                        (swap! errors conj (str prefix " (" (or (:name stage) "?") "): :when must have :branch or :param"))))))))))))

      ;; Validate :post section if present
      (when-let [post (:post data)]
        (when-not (map? post)
          (swap! errors conj ":post must be a map"))
        (when (map? post)
          (doseq [group-key [:always :on-success :on-failure]]
            (when-let [steps (get post group-key)]
              (when-not (vector? steps)
                (swap! errors conj (str ":post " (name group-key) " must be a vector")))
              (when (vector? steps)
                (doseq [[idx step] (map-indexed vector steps)]
                  (let [prefix (str ":post " (name group-key) " step " (inc idx))]
                    (when-not (map? step)
                      (swap! errors conj (str prefix ": must be a map")))
                    (when (map? step)
                      (when (str/blank? (:name step))
                        (swap! errors conj (str prefix ": missing :name")))
                      (when (str/blank? (:run step))
                        (swap! errors conj (str prefix ": missing :run"))))))))))))

    {:valid? (empty? @errors)
     :errors @errors}))

;; ---------------------------------------------------------------------------
;; Main entry point
;; ---------------------------------------------------------------------------

(defn parse-chengisfile
  "Parse a Chengisfile from disk. Returns:
   {:pipeline {:description ... :stages [...]}} on success
   {:error \"message\"} on failure

   Uses clojure.edn/read-string for safe, no-code-execution parsing.
   File size limited to 1MB for safety."
  [file-path]
  (try
    (let [f (io/file file-path)]
      (when-not (.exists f)
        (throw (ex-info "File not found" {:path file-path})))

      ;; Size check
      (when (> (.length f) (* 1024 1024))
        (throw (ex-info "Chengisfile exceeds 1MB size limit" {:size (.length f)})))

      (let [content (slurp f)
            ;; Safe EDN parsing — no tagged literals, no code execution
            data (edn/read-string {:readers {}} content)]

        ;; Validate
        (let [{:keys [valid? errors]} (validate-chengisfile data)]
          (if-not valid?
            {:error (str "Validation failed: " (str/join "; " errors))}
            ;; Convert to internal format
            (let [stages (mapv convert-stage (:stages data))
                  post-actions (when-let [post (:post data)]
                                 (let [convert-post-steps (fn [steps]
                                                           (when (seq steps)
                                                             (mapv convert-step steps)))]
                                   (cond-> {}
                                     (:always post)     (assoc :always (convert-post-steps (:always post)))
                                     (:on-success post) (assoc :on-success (convert-post-steps (:on-success post)))
                                     (:on-failure post) (assoc :on-failure (convert-post-steps (:on-failure post))))))
                  artifact-patterns (when-let [arts (:artifacts data)]
                                     (vec arts))
                  notify-configs (when-let [notifs (:notify data)]
                                   (vec notifs))
                  pipeline (cond-> {:stages stages}
                             (:description data)    (assoc :description (:description data))
                             (:container data)      (assoc :container (:container data))
                             (seq post-actions)      (assoc :post-actions post-actions)
                             (seq artifact-patterns) (assoc :artifacts artifact-patterns)
                             (seq notify-configs)    (assoc :notify notify-configs))]
              (log/info "Chengisfile parsed successfully:"
                        (count stages) "stages")
              {:pipeline pipeline})))))
    (catch Exception e
      {:error (str "Failed to parse Chengisfile: " (.getMessage e))})))
