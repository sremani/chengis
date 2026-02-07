(ns chengis.util
  "Shared utility functions used across multiple modules."
  (:require [clojure.edn :as edn])
  (:import [java.util UUID]))

(defn generate-id
  "Generate a random UUID string."
  []
  (str (UUID/randomUUID)))

(defn serialize-edn
  "Serialize a Clojure data structure to an EDN string. Returns nil for nil input."
  [data]
  (when data (pr-str data)))

(defn deserialize-edn
  "Deserialize an EDN string to a Clojure data structure. Returns nil for nil input."
  [s]
  (when s (edn/read-string s)))

(defn ensure-keyword
  "Coerce a status value to a keyword. Handles string, keyword, and nil inputs."
  [s]
  (cond
    (keyword? s) s
    (string? s)  (keyword s)
    :else        s))

(defn format-size
  "Format a byte count as a human-readable size string."
  [bytes]
  (cond
    (nil? bytes) "—"
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (format "%.1f KB" (/ bytes 1024.0))
    (< bytes (* 1024 1024 1024)) (format "%.1f MB" (/ bytes (* 1024.0 1024.0)))
    :else (format "%.2f GB" (/ bytes (* 1024.0 1024.0 1024.0)))))
