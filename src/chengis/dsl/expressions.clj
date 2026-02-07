(ns chengis.dsl.expressions
  "Expression resolver for ${{ }} template syntax in YAML pipelines.

   Supported expression types:
     ${{ parameters.name }}  → resolves to PARAM_NAME env var reference
     ${{ secrets.NAME }}     → resolved at runtime by executor secret injection
     ${{ env.NAME }}         → env var reference

   The resolver replaces expression tokens in string values throughout
   the pipeline definition map."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Expression parsing
;; ---------------------------------------------------------------------------

(def ^:private expression-pattern
  "Regex to match ${{ expr }} tokens."
  #"\$\{\{\s*([^}]+?)\s*\}\}")

(defn parse-expression
  "Parse a single expression string (without delimiters).
   Returns {:type :parameters/:secrets/:env :name \"...\"} or nil."
  [expr-str]
  (let [parts (str/split (str/trim expr-str) #"\." 2)]
    (when (= 2 (count parts))
      (let [[namespace name] parts]
        (case namespace
          "parameters" {:type :parameters :name name}
          "secrets"    {:type :secrets :name name}
          "env"        {:type :env :name name}
          nil)))))

(defn resolve-expression
  "Resolve a single parsed expression to its string value.

   Resolution rules:
     :parameters → PARAM_<UPPER_NAME> (injected as env var by executor)
     :secrets    → the secret name (resolved at runtime by executor)
     :env        → the env var name

   context-map can provide :parameters, :secrets, :env for early resolution."
  [{:keys [type name]} context-map]
  (case type
    :parameters (let [param-key (keyword name)
                      ;; Try to resolve from context if available
                      resolved (get-in context-map [:parameters param-key])]
                  (or resolved
                      ;; Return env var format for deferred resolution
                      (str "${PARAM_" (-> name str/upper-case (str/replace "-" "_")) "}")))
    :secrets    (let [resolved (get-in context-map [:secrets name])]
                  (or resolved
                      ;; Return marker for deferred resolution
                      (str "${" name "}")))
    :env        (let [resolved (get-in context-map [:env name])]
                  (or resolved
                      (str "${" name "}")))
    ;; Unknown expression type, return as-is
    nil))

(defn resolve-string
  "Resolve all ${{ }} expressions in a single string.
   Returns the string with expressions replaced."
  [s context-map]
  (if-not (string? s)
    s
    (str/replace s expression-pattern
                 (fn [[_full expr-str]]
                   (if-let [parsed (parse-expression expr-str)]
                     (or (resolve-expression parsed context-map)
                         (str "${{" expr-str "}}"))
                     ;; Not a recognized expression, leave as-is
                     (str "${{" expr-str "}}"))))))

(defn resolve-expressions
  "Recursively resolve all ${{ }} expressions in a data structure.
   Walks maps, vectors, and strings replacing expression tokens."
  [data context-map]
  (cond
    (string? data)  (resolve-string data context-map)
    (map? data)     (reduce-kv (fn [m k v]
                                 (assoc m k (resolve-expressions v context-map)))
                               {} data)
    (vector? data)  (mapv #(resolve-expressions % context-map) data)
    (seq? data)     (map #(resolve-expressions % context-map) data)
    :else           data))

(defn has-expressions?
  "Check if a string contains any ${{ }} expression tokens."
  [s]
  (when (string? s)
    (boolean (re-find expression-pattern s))))
