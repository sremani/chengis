(ns chengis.distributed.region
  "Region-aware agent dispatch with locality scoring.
   Agents declare a region; the master prefers same-region agents."
  (:require [clojure.string :as str]))

(defn same-region?
  "Check if an agent is in the same region as the master.
   Blank/nil regions are treated as 'no region' and never match."
  [master-region agent-region]
  (and (some? master-region)
       (some? agent-region)
       (not (str/blank? master-region))
       (not (str/blank? agent-region))
       (= master-region agent-region)))

(defn locality-bonus
  "Return a scoring bonus for region locality.
   Same region: +weight (default 0.3). Different or nil: 0.0"
  [master-region agent-region weight]
  (if (same-region? master-region agent-region)
    (double (if (some? weight) weight 0.3))
    0.0))

(defn region-aware-score
  "Augment an agent's base score with locality bonus.
   base-score is typically 0.0-1.0 from agent registry scoring.
   Returns capped at 1.5 to prevent excessive locality bias."
  [base-score master-region agent-region locality-weight]
  (min 1.5 (+ (double (if (some? base-score) base-score 0.0))
              (locality-bonus master-region agent-region locality-weight))))
