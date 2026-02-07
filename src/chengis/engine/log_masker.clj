(ns chengis.engine.log-masker
  "Log masking for secret values in build output.
   Replaces any occurrence of a secret value with *** in text."
  (:require [clojure.string :as str]))

(def ^:const mask-replacement "***")

(defn mask-secrets
  "Replace all occurrences of secret values in text with ***.
   secret-values is a set (or collection) of strings to mask.
   Returns the masked text, or the original text if no secrets."
  [text secret-values]
  (if (or (nil? text) (empty? secret-values))
    text
    (reduce (fn [t secret-val]
              (if (and secret-val (seq secret-val))
                (str/replace t secret-val mask-replacement)
                t))
            text
            secret-values)))
