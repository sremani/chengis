(ns chengis.engine.opa
  "OPA/Rego policy evaluation engine.
   Evaluates Rego policies against build context by invoking the OPA CLI.
   Feature-flagged under :opa-policies."
  (:require [clojure.data.json :as json]
            [chengis.engine.process :as process]
            [chengis.feature-flags :as feature-flags]
            [taoensso.timbre :as log])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Input assembly
;; ---------------------------------------------------------------------------

(defn build-opa-input
  "Assemble OPA input JSON map from build context."
  [build-ctx]
  {:build_id   (:build-id build-ctx)
   :job_id     (:job-id build-ctx)
   :org_id     (:org-id build-ctx)
   :branch     (:git-branch build-ctx)
   :author     (:git-author build-ctx)
   :parameters (:parameters build-ctx)
   :stage_name (:stage-name build-ctx)})

;; ---------------------------------------------------------------------------
;; Temp file helpers
;; ---------------------------------------------------------------------------

(defn- create-temp-file
  "Create a temporary file with the given prefix and suffix, write content to it."
  [prefix suffix content]
  (let [f (File/createTempFile prefix suffix)]
    (spit f content)
    f))

(defn- delete-quietly
  "Delete a file, ignoring any errors."
  [^File f]
  (when f
    (try (.delete f) (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; OPA policy evaluation
;; ---------------------------------------------------------------------------

(defn evaluate-opa-policy!
  "Evaluate a Rego policy against a build context using the OPA CLI.
   1. Writes rego-source to a temp .rego file
   2. Writes input JSON to a temp .json file
   3. Runs: opa eval -d <rego-file> -i <input-file> --format json \"data.<package-name>.allow\"
   4. Parses result JSON
   Returns {:result :allow/:deny :reason \"...\"}."
  [system policy-record build-ctx]
  (let [config (:config system)
        timeout (get-in config [:opa :eval-timeout-ms] 10000)
        opa-binary (get-in config [:opa :binary-path] "opa")
        rego-source (:rego-source policy-record)
        package-name (:package-name policy-record)
        input-data (build-opa-input build-ctx)
        rego-file (atom nil)
        input-file (atom nil)]
    (try
      (reset! rego-file (create-temp-file "opa-policy-" ".rego" rego-source))
      (reset! input-file (create-temp-file "opa-input-" ".json" (json/write-str input-data)))
      ;; Sanitize package-name to prevent shell injection (only allow alphanumeric, dots, underscores)
      (when-not (re-matches #"[a-zA-Z0-9._]+" (or package-name ""))
        (throw (ex-info "Invalid OPA package name" {:package-name package-name})))
      (let [cmd (str opa-binary " eval"
                     " -d " (.getAbsolutePath ^File @rego-file)
                     " -i " (.getAbsolutePath ^File @input-file)
                     " --format json"
                     " \"data." package-name ".allow\"")
            result (process/execute-command {:command cmd :timeout timeout})]
        (cond
          ;; Timed out — check first because timed-out processes may also have non-zero exit
          (:timed-out? result)
          (do (log/warn "OPA policy evaluation timed out" {:policy (:name policy-record)})
              {:result :deny :reason (str "OPA policy evaluation timed out after " timeout "ms")})

          ;; OPA binary not found (exit 127) or command not found
          (= 127 (:exit-code result))
          (do (log/warn "OPA binary not available, skipping policy evaluation")
              {:result :allow :reason "OPA binary not available — skipping"})

          ;; Successful execution
          (zero? (:exit-code result))
          (let [parsed (json/read-str (:stdout result) :key-fn keyword)
                value (get-in parsed [:result 0 :expressions 0 :value])]
            (if value
              {:result :allow :reason "OPA policy allowed"}
              {:result :deny :reason "OPA policy denied"}))

          ;; Other errors
          :else
          (do (log/warn "OPA evaluation failed" {:exit-code (:exit-code result)
                                                  :stderr (:stderr result)})
              {:result :deny :reason (str "OPA evaluation error: " (:stderr result))})))
      (catch java.io.IOException e
        (log/warn "OPA binary not available (IOException)" {:error (.getMessage e)})
        {:result :allow :reason "OPA binary not available — skipping"})
      (catch Exception e
        (log/error "OPA evaluation unexpected error" {:error (.getMessage e)})
        {:result :deny :reason (str "OPA evaluation error: " (.getMessage e))})
      (finally
        (delete-quietly @rego-file)
        (delete-quietly @input-file)))))

;; ---------------------------------------------------------------------------
;; Rego syntax validation
;; ---------------------------------------------------------------------------

(defn validate-rego-syntax
  "Validate Rego source syntax by running `opa check <file>`.
   Returns {:valid? bool :errors \"...\"}."
  [config rego-source]
  (let [opa-binary (get-in config [:opa :binary-path] "opa")
        timeout (get-in config [:opa :eval-timeout-ms] 10000)
        rego-file (atom nil)]
    (try
      (reset! rego-file (create-temp-file "opa-check-" ".rego" rego-source))
      (let [cmd (str opa-binary " check " (.getAbsolutePath ^File @rego-file))
            result (process/execute-command {:command cmd :timeout timeout})]
        (if (zero? (:exit-code result))
          {:valid? true :errors nil}
          {:valid? false :errors (str (:stderr result) (:stdout result))}))
      (catch java.io.IOException _e
        {:valid? false :errors "OPA binary not available"})
      (catch Exception e
        {:valid? false :errors (.getMessage e)})
      (finally
        (delete-quietly @rego-file)))))
