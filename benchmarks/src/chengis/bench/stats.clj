(ns chengis.bench.stats
  "Statistical functions for benchmark result analysis.")

(defn mean
  "Arithmetic mean of a sequence of numbers."
  [xs]
  (if (empty? xs)
    0.0
    (/ (double (reduce + xs)) (count xs))))

(defn median
  "Median of a sequence of numbers."
  [xs]
  (if (empty? xs)
    0.0
    (let [sorted (sort xs)
          n      (count sorted)
          mid    (quot n 2)]
      (if (odd? n)
        (double (nth sorted mid))
        (/ (+ (double (nth sorted (dec mid)))
              (double (nth sorted mid)))
           2.0)))))

(defn stddev
  "Population standard deviation."
  [xs]
  (if (< (count xs) 2)
    0.0
    (let [m   (mean xs)
          ssq (reduce + (map #(Math/pow (- (double %) m) 2) xs))]
      (Math/sqrt (/ ssq (count xs))))))

(defn percentile
  "Calculate the p-th percentile (0-100) of a sorted sequence."
  [xs p]
  (if (empty? xs)
    0.0
    (let [sorted (sort xs)
          n      (count sorted)
          idx    (* (/ (double p) 100.0) (dec n))
          lo     (int (Math/floor idx))
          hi     (min (int (Math/ceil idx)) (dec n))
          frac   (- idx lo)]
      (+ (* (- 1.0 frac) (double (nth sorted lo)))
         (* frac (double (nth sorted hi)))))))

(defn min-val [xs] (if (empty? xs) 0.0 (double (apply min xs))))
(defn max-val [xs] (if (empty? xs) 0.0 (double (apply max xs))))

(defn summarize
  "Produce a summary map from a sequence of numeric measurements."
  [xs]
  {:n      (count xs)
   :mean   (mean xs)
   :median (median xs)
   :stddev (stddev xs)
   :min    (min-val xs)
   :max    (max-val xs)
   :p50    (percentile xs 50)
   :p90    (percentile xs 90)
   :p95    (percentile xs 95)
   :p99    (percentile xs 99)})
