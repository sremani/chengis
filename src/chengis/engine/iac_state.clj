(ns chengis.engine.iac-state
  "IaC state management — versioning, compression, locking, and diffing.
   States are stored compressed (gzip + base64) with SHA-256 checksums
   and auto-incrementing version numbers per project+workspace."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [clojure.data.json :as json]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]
           [java.util Base64]
           [java.io ByteArrayOutputStream ByteArrayInputStream]
           [java.util.zip GZIPOutputStream GZIPInputStream]))

;; ---------------------------------------------------------------------------
;; Compression helpers
;; ---------------------------------------------------------------------------

(defn- compress
  "Gzip compress a string and return base64-encoded result."
  [^String data]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [gzos (GZIPOutputStream. baos)]
      (.write gzos (.getBytes data "UTF-8")))
    (.encodeToString (Base64/getEncoder) (.toByteArray baos))))

(defn- decompress
  "Base64 decode and gunzip a compressed string."
  [^String data]
  (let [decoded (.decode (Base64/getDecoder) data)
        bais (ByteArrayInputStream. decoded)
        baos (ByteArrayOutputStream.)]
    (with-open [gzis (GZIPInputStream. bais)]
      (let [buf (byte-array 4096)]
        (loop []
          (let [n (.read gzis buf)]
            (when (pos? n)
              (.write baos buf 0 n)
              (recur))))))
    (.toString baos "UTF-8")))

(defn- sha256
  "Compute SHA-256 hex digest of a string."
  [^String data]
  (let [md (MessageDigest/getInstance "SHA-256")
        digest (.digest md (.getBytes data "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

;; ---------------------------------------------------------------------------
;; State CRUD
;; ---------------------------------------------------------------------------

(defn save-state!
  "Save a new state version. Compresses state content, computes checksum,
   auto-increments version number.
   Options:
     :org-id          - organization ID
     :project-id      - IaC project ID
     :state-content   - raw state string (JSON)
     :tool-type       - terraform/pulumi/cloudformation
     :workspace-name  - workspace/stack name (default \"default\")
     :created-by      - user who triggered the save
     :max-size        - max uncompressed size in bytes (default 50MB)
   Returns the saved state metadata (without state_data)."
  [ds {:keys [org-id project-id state-content tool-type workspace-name
              created-by max-size]
       :or {workspace-name "default" max-size (* 50 1024 1024)}}]
  (when (> (count state-content) max-size)
    (throw (ex-info "State content exceeds max size"
                    {:size (count state-content) :max-size max-size})))
  (let [checksum (sha256 state-content)
        compressed (compress state-content)
        size-bytes (count (.getBytes state-content "UTF-8"))
        ;; Get current max version for this project+workspace
        latest (jdbc/execute-one! ds
                 (sql/format {:select [[[:coalesce [:max :version] 0] :max-ver]]
                              :from :iac-states
                              :where [:and
                                      [:= :project-id project-id]
                                      [:= :workspace-name workspace-name]]})
                 {:builder-fn rs/as-unqualified-kebab-maps})
        next-version (inc (or (:max-ver latest) 0))
        id (util/generate-id)
        row {:id id
             :org-id (or org-id "default-org")
             :project-id project-id
             :workspace-name workspace-name
             :version next-version
             :state-data compressed
             :state-hash checksum
             :state-size size-bytes
             :tool-type (when tool-type (name tool-type))
             :created-by created-by}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :iac-states :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (log/info "Saved IaC state version" next-version "for project" project-id
              "workspace" workspace-name "(" size-bytes "bytes)")
    (dissoc row :state-data)))

(defn get-latest-state
  "Get the latest state for a project, optionally filtered by workspace.
   Returns the state with decompressed :state-content field."
  [ds project-id & {:keys [workspace-name]}]
  (let [query (cond-> {:select :*
                        :from :iac-states
                        :where [:= :project-id project-id]
                        :order-by [[:version :desc]]
                        :limit 1}
                workspace-name
                (assoc :where [:and
                               [:= :project-id project-id]
                               [:= :workspace-name workspace-name]]))
        row (jdbc/execute-one! ds (sql/format query)
              {:builder-fn rs/as-unqualified-kebab-maps})]
    (when row
      (-> row
          (assoc :state-content (when (:state-data row)
                                  (try (decompress (:state-data row))
                                       (catch Exception e
                                         (log/warn "Failed to decompress state:" (.getMessage e))
                                         nil))))
          (dissoc :state-data)))))

(defn get-state-version
  "Get a specific version of state for a project."
  [ds project-id version & {:keys [workspace-name]}]
  (let [query {:select :*
               :from :iac-states
               :where (if workspace-name
                        [:and
                         [:= :project-id project-id]
                         [:= :version version]
                         [:= :workspace-name workspace-name]]
                        [:and
                         [:= :project-id project-id]
                         [:= :version version]])}
        row (jdbc/execute-one! ds (sql/format query)
              {:builder-fn rs/as-unqualified-kebab-maps})]
    (when row
      (-> row
          (assoc :state-content (when (:state-data row)
                                  (try (decompress (:state-data row))
                                       (catch Exception e
                                         (log/warn "Failed to decompress state:" (.getMessage e))
                                         nil))))
          (dissoc :state-data)))))

(defn list-state-versions
  "List state version metadata (without state_data) for a project.
   Returns versions ordered by version desc."
  [ds project-id & {:keys [workspace-name limit]
                    :or {limit 50}}]
  (let [query (cond-> {:select [:id :org-id :project-id :workspace-name
                                :version :state-hash :state-size :tool-type
                                :created-by :created-at]
                        :from :iac-states
                        :where [:= :project-id project-id]
                        :order-by [[:version :desc]]
                        :limit limit}
                workspace-name
                (assoc :where [:and
                               [:= :project-id project-id]
                               [:= :workspace-name workspace-name]]))]
    (jdbc/execute! ds (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

;; ---------------------------------------------------------------------------
;; State Diffing
;; ---------------------------------------------------------------------------

(defn diff-states
  "Compare two decompressed state strings (as JSON objects).
   Returns {:added [...] :removed [...] :changed [...]}
   where each entry is a resource identifier."
  [state-a state-b]
  (try
    (let [parse (fn [s] (try (json/read-str s :key-fn keyword) (catch Exception _ {})))
          a-parsed (parse state-a)
          b-parsed (parse state-b)
          ;; Extract resource keys — works for Terraform state format
          extract-resources (fn [state]
                              (let [resources (or (:resources state) [])]
                                (into {}
                                  (map (fn [r]
                                         [(str (:type r) "." (:name r))
                                          (dissoc r :instances)])
                                       resources))))
          a-resources (extract-resources a-parsed)
          b-resources (extract-resources b-parsed)
          a-keys (set (keys a-resources))
          b-keys (set (keys b-resources))
          added (vec (clojure.set/difference b-keys a-keys))
          removed (vec (clojure.set/difference a-keys b-keys))
          common (clojure.set/intersection a-keys b-keys)
          changed (vec (filter (fn [k]
                                 (not= (get a-resources k) (get b-resources k)))
                               common))]
      {:added added
       :removed removed
       :changed changed})
    (catch Exception e
      (log/warn "Failed to diff states:" (.getMessage e))
      {:added [] :removed [] :changed []})))

;; ---------------------------------------------------------------------------
;; Locking
;; ---------------------------------------------------------------------------

(defn acquire-lock!
  "Acquire a state lock for a project+workspace.
   Uses INSERT with ON CONFLICT DO NOTHING for atomic lock acquisition.
   If an existing lock is expired (expires_at < now), removes it first.
   Returns true if lock acquired, false if already locked."
  [ds project-id user-id & {:keys [workspace-name reason timeout-ms]
                             :or {workspace-name "default"
                                  timeout-ms (* 30 60 1000)}}]
  ;; First, clean up expired locks
  (jdbc/execute-one! ds
    (sql/format {:delete-from :iac-state-locks
                 :where [:and
                         [:= :project-id project-id]
                         [:= :workspace-name workspace-name]
                         [:< :expires-at [:raw "CURRENT_TIMESTAMP"]]]}))
  ;; Try to insert a new lock
  (let [id (util/generate-id)
        timeout-seconds (/ timeout-ms 1000)
        row {:id id
             :project-id project-id
             :workspace-name workspace-name
             :locked-by user-id
             :lock-reason reason
             :expires-at [:raw (str "datetime(CURRENT_TIMESTAMP, '+" (int timeout-seconds) " seconds')")]}
        result (jdbc/execute-one! ds
                 (sql/format {:insert-into :iac-state-locks
                              :values [row]
                              :on-conflict [:project-id :workspace-name]
                              :do-nothing true}))]
    ;; Check if we actually inserted — if the lock already existed, update-count is 0
    (let [check (jdbc/execute-one! ds
                  (sql/format {:select :*
                               :from :iac-state-locks
                               :where [:and
                                       [:= :project-id project-id]
                                       [:= :workspace-name workspace-name]
                                       [:= :locked-by user-id]]})
                  {:builder-fn rs/as-unqualified-kebab-maps})]
      (boolean check))))

(defn release-lock!
  "Release a state lock for a project+workspace. Returns true if released."
  [ds project-id & {:keys [workspace-name]
                    :or {workspace-name "default"}}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :iac-state-locks
                              :where [:and
                                      [:= :project-id project-id]
                                      [:= :workspace-name workspace-name]]}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn get-lock
  "Get the current lock for a project+workspace, or nil if unlocked."
  [ds project-id & {:keys [workspace-name]
                    :or {workspace-name "default"}}]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :iac-state-locks
                 :where [:and
                         [:= :project-id project-id]
                         [:= :workspace-name workspace-name]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn force-unlock!
  "Force-remove a state lock regardless of owner. Returns true if removed."
  [ds project-id & {:keys [workspace-name]
                    :or {workspace-name "default"}}]
  (log/warn "Force-unlocking state for project" project-id "workspace" workspace-name)
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :iac-state-locks
                              :where [:and
                                      [:= :project-id project-id]
                                      [:= :workspace-name workspace-name]]}))]
    (pos? (or (:next.jdbc/update-count result) 0))))
