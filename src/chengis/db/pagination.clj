(ns chengis.db.pagination
  "Shared cursor-based pagination utilities for all store modules.
   Cursors encode a (timestamp, id) pair as a Base64 string for URL safety."
  (:require [clojure.string :as str])
  (:import [java.util Base64]))

(defn encode-cursor
  "Encode a timestamp and id into an opaque Base64 cursor string."
  [timestamp id]
  (when (and timestamp id)
    (.encodeToString (Base64/getUrlEncoder)
                     (.getBytes (str timestamp "|" id) "UTF-8"))))

(defn decode-cursor
  "Decode a cursor string into {:timestamp :id}. Returns nil if invalid."
  [cursor-str]
  (try
    (when (and cursor-str (not (str/blank? cursor-str)))
      (let [decoded (String. (.decode (Base64/getUrlDecoder) cursor-str) "UTF-8")
            parts (str/split decoded #"\|" 2)]
        (when (= 2 (count parts))
          {:timestamp (first parts)
           :id (second parts)})))
    (catch Exception _ nil)))

(defn apply-cursor-where
  "Add cursor-based pagination conditions to a HoneySQL where clause.
   For :desc direction: WHERE (ts < cursor-ts) OR (ts = cursor-ts AND id < cursor-id)
   For :asc direction: WHERE (ts > cursor-ts) OR (ts = cursor-ts AND id > cursor-id)
   timestamp-col and id-col are HoneySQL column keywords."
  [where-clause cursor-data timestamp-col id-col direction]
  (if-not cursor-data
    where-clause
    (let [{:keys [timestamp id]} cursor-data
          op (if (= direction :asc) :> :<)
          cursor-cond [:or
                       [op timestamp-col timestamp]
                       [:and
                        [:= timestamp-col timestamp]
                        [op id-col id]]]]
      (if where-clause
        [:and where-clause cursor-cond]
        cursor-cond))))

(defn paginated-response
  "Wrap a result set in a pagination envelope.
   Fetches limit+1 items; returns limit items with has-more flag.
   id-key and timestamp-key extract cursor fields from the last item."
  [items limit id-key timestamp-key]
  (let [has-more (> (count items) limit)
        page-items (if has-more (vec (take limit items)) (vec items))
        last-item (last page-items)]
    {:items page-items
     :has-more has-more
     :next-cursor (when (and has-more last-item)
                    (encode-cursor (get last-item timestamp-key)
                                   (get last-item id-key)))}))
