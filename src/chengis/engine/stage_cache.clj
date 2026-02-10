(ns chengis.engine.stage-cache
  "Build result caching: skip stages when inputs match a previous successful run.
   Stage fingerprint = SHA-256(git-commit | stage-name | sorted-commands | env-hash)."
  (:require [clojure.data.json :as json]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [chengis.feature-flags :as ff]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Fingerprint computation
;; ---------------------------------------------------------------------------

(defn- sha256
  "Compute SHA-256 hash of a string. Returns hex string."
  [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.getBytes s "UTF-8")]
    (.update md bytes)
    (let [digest (.digest md)]
      (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(def ^:private build-specific-env-keys
  "Environment variable keys that are build-specific and should be excluded
   from the stage fingerprint. Including these would cause cache misses on
   every build since they change every time."
  #{"BUILD_ID" "BUILD_NUMBER" "WORKSPACE" "JOB_NAME"})

(defn stage-fingerprint
  "Compute a deterministic fingerprint for a stage's inputs.
   Inputs: git commit SHA, stage name, sorted step commands, and env hash.
   Build-specific env vars (BUILD_ID, BUILD_NUMBER, WORKSPACE, JOB_NAME)
   are excluded from the fingerprint since they change every build."
  [git-commit stage-def env]
  (let [stage-name (:stage-name stage-def)
        ;; Sort step commands for deterministic ordering
        commands (sort (map :command (:steps stage-def)))
        commands-str (pr-str commands)
        ;; Hash environment keys+values (sorted), excluding build-specific vars
        stable-env (remove (fn [[k _]] (contains? build-specific-env-keys k))
                           (or env {}))
        env-str (pr-str (sort stable-env))
        input (str (or git-commit "no-commit") "|"
                   stage-name "|"
                   commands-str "|"
                   env-str)]
    (sha256 input)))

;; ---------------------------------------------------------------------------
;; Database operations
;; ---------------------------------------------------------------------------

(defn check-stage-cache
  "Query the stage_cache table for a matching fingerprint.
   Returns the cached stage result map or nil."
  [ds job-id fingerprint]
  (when-let [row (jdbc/execute-one! ds
                   (sql/format {:select [:*]
                                :from [:stage-cache]
                                :where [:and [:= :job-id job-id]
                                             [:= :fingerprint fingerprint]]})
                   {:builder-fn rs/as-unqualified-kebab-maps})]
    (try
      (json/read-str (:stage-result row) :key-fn keyword)
      (catch Exception e
        (log/warn "Failed to parse cached stage result:" (.getMessage e))
        nil))))

(defn save-stage-result!
  "Persist a stage result keyed by fingerprint."
  [ds {:keys [job-id fingerprint stage-name stage-result git-commit org-id]}]
  (let [id (util/generate-id)]
    (try
      (jdbc/execute-one! ds
        (sql/format {:insert-into :stage-cache
                     :values [{:id id
                               :job-id job-id
                               :fingerprint fingerprint
                               :stage-name stage-name
                               :stage-result (json/write-str stage-result)
                               :git-commit git-commit
                               :org-id (or org-id "default-org")}]})
        {:builder-fn rs/as-unqualified-kebab-maps})
      (catch Exception e
        ;; Unique constraint — already cached
        (log/debug "Stage result already cached for fingerprint:" fingerprint)
        nil))))

;; ---------------------------------------------------------------------------
;; Integration
;; ---------------------------------------------------------------------------

(defn should-skip-stage?
  "Check if a stage can be skipped based on cached results.
   Returns {:skip? true :cached-result <result>} or {:skip? false}."
  [system build-ctx stage-def]
  (let [config (:config system)
        ds (:db system)
        git-commit (get-in build-ctx [:env "GIT_COMMIT"])]
    (if (and ds
             (ff/enabled? config :build-result-cache)
             git-commit)
      (let [fp (stage-fingerprint git-commit stage-def (:env build-ctx))
            job-id (:job-id build-ctx)
            cached (check-stage-cache ds job-id fp)]
        (if cached
          (do
            (log/info "Stage cache HIT for" (:stage-name stage-def)
                      "fingerprint:" (subs fp 0 12) "...")
            {:skip? true :cached-result cached :fingerprint fp})
          {:skip? false :fingerprint fp}))
      {:skip? false})))
