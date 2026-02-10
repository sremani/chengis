(ns chengis.engine.build-compare
  "Comparison engine for two builds — produces a structured diff of
   stages, steps, timing, status, and artifacts."
  (:require [clojure.string :as str]
            [clojure.set :as set])
  (:import [java.time Instant Duration]
           [java.time.format DateTimeParseException]))

(defn- parse-duration
  "Parse start/end timestamps to compute duration in seconds.
   Handles ISO-8601 and SQLite `YYYY-MM-DD HH:MM:SS` formats.
   Returns nil when either timestamp is nil or unparseable."
  [started-at completed-at]
  (when (and started-at completed-at)
    (try
      (let [normalize (fn [s]
                        ;; SQLite uses space separator, ISO-8601 uses T
                        (str/replace (str s) #" " "T"))
            start (Instant/parse (let [n (normalize started-at)]
                                   (if (str/ends-with? n "Z") n (str n "Z"))))
            end   (Instant/parse (let [n (normalize completed-at)]
                                   (if (str/ends-with? n "Z") n (str n "Z"))))]
        (.getSeconds (Duration/between start end)))
      (catch DateTimeParseException _ nil)
      (catch Exception _ nil))))

(defn- match-by-name
  "Match two collections by a name key, returning [{:name ... :a ... :b ...}].
   Performs a full outer join: items only in coll-a, only in coll-b, or in both."
  [coll-a coll-b name-key]
  (let [idx-a (group-by name-key coll-a)
        idx-b (group-by name-key coll-b)
        all-names (distinct (concat (map name-key coll-a) (map name-key coll-b)))]
    (mapv (fn [n]
            {:name n
             :a    (first (get idx-a n))
             :b    (first (get idx-b n))})
          all-names)))

(defn- compare-stages
  "Compare matched stages and their steps. Returns a seq of stage comparison maps."
  [stages-a steps-a stages-b steps-b]
  (let [matched (match-by-name stages-a stages-b :stage-name)
        steps-by-stage-a (group-by :stage-name steps-a)
        steps-by-stage-b (group-by :stage-name steps-b)]
    (mapv (fn [{:keys [name a b]}]
            (let [dur-a (when a (parse-duration (:started-at a) (:completed-at a)))
                  dur-b (when b (parse-duration (:started-at b) (:completed-at b)))
                  sa (get steps-by-stage-a name [])
                  sb (get steps-by-stage-b name [])
                  matched-steps (match-by-name sa sb :step-name)
                  step-diffs (mapv (fn [{step-name :name step-a :a step-b :b}]
                                    (let [sd-a (when step-a (parse-duration (:started-at step-a) (:completed-at step-a)))
                                          sd-b (when step-b (parse-duration (:started-at step-b) (:completed-at step-b)))]
                                      (cond-> {:step-name step-name}
                                        step-a (assoc :status-a (:status step-a)
                                                      :exit-code-a (:exit-code step-a)
                                                      :duration-a-s sd-a)
                                        step-b (assoc :status-b (:status step-b)
                                                      :exit-code-b (:exit-code step-b)
                                                      :duration-b-s sd-b)
                                        (and sd-a sd-b) (assoc :duration-delta-s (- sd-b sd-a)))))
                                  matched-steps)]
              (cond-> {:stage-name name}
                a (assoc :status-a (:status a) :duration-a-s dur-a)
                b (assoc :status-b (:status b) :duration-b-s dur-b)
                (and dur-a dur-b) (assoc :duration-delta-s (- dur-b dur-a))
                (seq step-diffs) (assoc :steps step-diffs))))
          matched)))

(defn- compare-artifacts
  "Diff artifacts between two builds by filename."
  [artifacts-a artifacts-b]
  (let [idx-a (into {} (map (juxt :filename identity) artifacts-a))
        idx-b (into {} (map (juxt :filename identity) artifacts-b))
        names-a (set (keys idx-a))
        names-b (set (keys idx-b))
        only-a (sort (set/difference names-a names-b))
        only-b (sort (set/difference names-b names-a))
        both   (sort (set/intersection names-a names-b))
        size-changes (for [f both
                           :let [sa (:size-bytes (get idx-a f))
                                 sb (:size-bytes (get idx-b f))]
                           :when (and sa sb (not= sa sb))]
                       {:filename f :size-a sa :size-b sb :delta (- sb sa)})]
    {:only-in-a (vec only-a)
     :only-in-b (vec only-b)
     :in-both (vec both)
     :size-changes (vec size-changes)}))

(defn compare-builds
  "Compare two builds and produce a structured diff.
   build-a, build-b: full build records with stages, steps.
   Returns:
   {:build-a {...} :build-b {...}
    :summary {:status-changed? bool :duration-delta-s N
              :stages-added [...] :stages-removed [...]}
    :stages [{:stage-name ... :status-a ... :status-b ... ...}]
    :artifacts {:only-in-a [...] :only-in-b [...] :in-both [...] :size-changes [...]}}"
  [build-a stages-a steps-a artifacts-a
   build-b stages-b steps-b artifacts-b]
  (let [dur-a (parse-duration (:started-at build-a) (:completed-at build-a))
        dur-b (parse-duration (:started-at build-b) (:completed-at build-b))
        stage-names-a (set (map :stage-name stages-a))
        stage-names-b (set (map :stage-name stages-b))
        stages-added  (sort (set/difference stage-names-b stage-names-a))
        stages-removed (sort (set/difference stage-names-a stage-names-b))
        stage-diffs (compare-stages stages-a steps-a stages-b steps-b)
        artifact-diff (compare-artifacts (or artifacts-a []) (or artifacts-b []))]
    {:build-a build-a
     :build-b build-b
     :summary {:status-changed? (not= (:status build-a) (:status build-b))
               :duration-a-s dur-a
               :duration-b-s dur-b
               :duration-delta-s (when (and dur-a dur-b) (- dur-b dur-a))
               :stages-added (vec stages-added)
               :stages-removed (vec stages-removed)}
     :stages stage-diffs
     :artifacts artifact-diff}))
