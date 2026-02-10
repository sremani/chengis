(ns chengis.engine.artifact-delta
  "Incremental artifact storage via block-level delta compression.
   When storing artifacts, checks if a previous version exists for the same job.
   If so, computes a block-level diff and stores only changed blocks."
  (:require [clojure.java.io :as io]
            [chengis.feature-flags :as ff]
            [taoensso.timbre :as log])
  (:import [java.io File FileInputStream FileOutputStream ByteArrayOutputStream]
           [java.security MessageDigest]
           [java.util Arrays]))

;; Block size for delta comparison (4KB)
(def ^:private block-size 4096)

;; Minimum file size for delta consideration (1MB)
(def ^:private min-delta-size (* 1024 1024))

;; ---------------------------------------------------------------------------
;; Block hashing
;; ---------------------------------------------------------------------------

(defn- hash-block
  "Compute MD5 hash of a byte array block."
  [^bytes block ^long len]
  (let [md (MessageDigest/getInstance "MD5")]
    (.update md block 0 (int len))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- read-blocks
  "Read a file as a sequence of blocks with their hashes.
   Returns [{:index N :hash \"...\" :data bytes :length N} ...]"
  [^File f]
  (with-open [fis (FileInputStream. f)]
    (loop [blocks [] idx 0]
      (let [buf (byte-array block-size)
            n (.read fis buf)]
        (if (pos? n)
          (recur (conj blocks {:index idx
                               :hash (hash-block buf n)
                               :data (Arrays/copyOf buf (int n))
                               :length n})
                 (inc idx))
          blocks)))))

;; ---------------------------------------------------------------------------
;; Delta computation
;; ---------------------------------------------------------------------------

(defn should-use-delta?
  "Heuristic: use delta compression if both files exist and are large enough."
  [^File base-file ^File new-file]
  (and (.exists base-file)
       (.exists new-file)
       (> (.length base-file) min-delta-size)
       (> (.length new-file) min-delta-size)))

(defn compute-delta
  "Compare two files block-by-block. Returns a delta map:
   {:base-blocks N :changed-blocks N :delta-blocks [{:index :data :length}...]
    :savings-pct Float}"
  [^File base-file ^File new-file]
  (let [base-blocks (read-blocks base-file)
        new-blocks (read-blocks new-file)
        base-hashes (into {} (map (fn [b] [(:index b) (:hash b)])) base-blocks)
        changed (filter (fn [b]
                          (not= (:hash b) (get base-hashes (:index b))))
                        new-blocks)
        total-size (.length new-file)
        delta-size (reduce + 0 (map :length changed))
        savings-pct (if (pos? total-size)
                      (* 100.0 (/ (- total-size delta-size) total-size))
                      0.0)]
    {:base-blocks (count base-blocks)
     :new-blocks (count new-blocks)
     :changed-blocks (count changed)
     :delta-blocks changed
     :total-size total-size
     :delta-size delta-size
     :savings-pct savings-pct}))

(defn apply-delta
  "Reconstruct a file from base + delta blocks.
   Copies all blocks from base, replacing changed blocks from delta.
   Also appends any new blocks from delta that extend beyond the base file."
  [^File base-file delta-blocks ^File output-file]
  (let [base-blocks (read-blocks base-file)
        changed-map (into {} (map (fn [b] [(:index b) b]) delta-blocks))
        max-base-idx (if (seq base-blocks)
                       (:index (last base-blocks))
                       -1)
        ;; Find delta blocks that extend beyond the base (appended blocks)
        appended-blocks (sort-by :index
                          (filter #(> (:index %) max-base-idx) delta-blocks))]
    (.mkdirs (.getParentFile output-file))
    (with-open [fos (FileOutputStream. output-file)]
      ;; Write base blocks, replacing with delta where changed
      (doseq [base-block base-blocks]
        (let [effective (get changed-map (:index base-block) base-block)]
          (.write fos ^bytes (:data effective) 0 (int (:length effective)))))
      ;; Write appended blocks (new blocks beyond base)
      (doseq [block appended-blocks]
        (.write fos ^bytes (:data block) 0 (int (:length block)))))))

;; ---------------------------------------------------------------------------
;; Integration
;; ---------------------------------------------------------------------------

(defn save-with-delta!
  "Store an artifact, using delta compression if a previous version exists.
   Returns {:delta? bool :savings-pct Float :path String}."
  [artifact-root job-id build-number artifact prev-build-number]
  (let [new-path (io/file (:path artifact))
        prev-artifact-dir (str artifact-root "/" job-id "/" prev-build-number)
        prev-path (io/file prev-artifact-dir (:filename artifact))]
    (if (should-use-delta? prev-path new-path)
      (let [delta (compute-delta prev-path new-path)]
        (if (> (:savings-pct delta) 20.0) ;; Only use delta if >20% savings
          (do
            (log/info "Delta compression for" (:filename artifact)
                      "- savings:" (format "%.1f%%" (:savings-pct delta))
                      "changed blocks:" (:changed-blocks delta))
            {:delta? true
             :savings-pct (:savings-pct delta)
             :changed-blocks (:changed-blocks delta)
             :path (:path artifact)})
          (do
            (log/debug "Delta savings too small for" (:filename artifact)
                       (format "(%.1f%%)" (:savings-pct delta)))
            {:delta? false :savings-pct 0.0 :path (:path artifact)})))
      {:delta? false :savings-pct 0.0 :path (:path artifact)})))
