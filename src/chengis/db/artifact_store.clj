(ns chengis.db.artifact-store
  "Storage for build artifacts metadata."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [clojure.java.io :as io]))

(defn- fix-hash-key
  "next.jdbc kebab-cases sha256_hash → :sha-256-hash.  Normalize to :sha256-hash."
  [row]
  (when row
    (let [v (or (:sha256-hash row) (:sha-256-hash row))]
      (-> row
          (dissoc :sha-256-hash)
          (assoc :sha256-hash v)))))

(defn save-artifact!
  "Save artifact metadata to the database."
  [ds {:keys [build-id filename path size-bytes content-type sha256-hash]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :build-artifacts
                   :values [(cond-> {:id id
                                     :build-id build-id
                                     :filename filename
                                     :path path
                                     :size-bytes size-bytes
                                     :content-type (or content-type "application/octet-stream")}
                              sha256-hash (assoc :sha256-hash sha256-hash))]}))
    {:id id :build-id build-id :filename filename :path path
     :size-bytes size-bytes :content-type content-type
     :sha256-hash sha256-hash}))

(defn list-artifacts
  "List all artifacts for a build."
  [ds build-id]
  (mapv fix-hash-key
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :build-artifacts
                   :where [:= :build-id build-id]
                   :order-by [[:filename :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-artifact
  "Get a single artifact by build-id and filename."
  [ds build-id filename]
  (fix-hash-key
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :build-artifacts
                   :where [:and [:= :build-id build-id]
                                [:= :filename filename]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn- compute-sha256-for-verify
  "Compute SHA-256 of a file for verification. Returns hex string or nil."
  [^java.io.File file]
  (try
    (let [digest (java.security.MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (with-open [fis (java.io.FileInputStream. file)]
        (loop []
          (let [n (.read fis buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur)))))
      (format "%064x" (BigInteger. 1 (.digest digest))))
    (catch Exception _e nil)))

(defn verify-artifact-hash
  "Verify the integrity of a stored artifact by recomputing its SHA-256 hash.
   Returns {:valid true/false/nil :expected :computed}."
  [ds build-id filename]
  (let [artifact (get-artifact ds build-id filename)]
    (if-not artifact
      {:valid nil :reason "Artifact not found"}
      (if-not (:sha256-hash artifact)
        {:valid nil :reason "No hash stored (pre-checksum artifact)"}
        (let [file (io/file (:path artifact))]
          (if-not (.exists file)
            {:valid nil :reason "Artifact file missing from disk"}
            (let [computed (compute-sha256-for-verify file)]
              {:valid (= computed (:sha256-hash artifact))
               :expected (:sha256-hash artifact)
               :computed computed})))))))

(defn delete-artifacts-for-build!
  "Delete all artifact records for a build."
  [ds build-id]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :build-artifacts
                 :where [:= :build-id build-id]})))
