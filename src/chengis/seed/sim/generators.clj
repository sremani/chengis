(ns chengis.seed.sim.generators
  "PRNG-based generators for simulation data: timestamps, statuses, git metadata, commits."
  (:require [chengis.seed.sim.data :as data])
  (:import [java.time DayOfWeek LocalDate LocalDateTime ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util Random]))

;; ---------------------------------------------------------------------------
;; PRNG helpers
;; ---------------------------------------------------------------------------

(defn next-double
  "Return a random double in [0, 1)."
  [^Random rng]
  (.nextDouble rng))

(defn next-int
  "Return a random int in [0, n)."
  [^Random rng n]
  (.nextInt rng (int n)))

(defn next-gaussian
  "Return a random gaussian (normal distribution) value."
  [^Random rng]
  (.nextGaussian rng))

(defn weighted-choice
  "Pick a value from a seq of [value weight] pairs using the given RNG."
  [^Random rng choices]
  (let [total  (reduce + (map second choices))
        roll   (* (next-double rng) total)]
    (loop [remaining choices
           acc       0.0]
      (let [[value weight] (first remaining)
            new-acc (+ acc weight)]
        (if (or (< roll new-acc) (nil? (next remaining)))
          value
          (recur (rest remaining) new-acc))))))

(defn pick
  "Pick a random element from a collection."
  [^Random rng coll]
  (let [v (vec coll)]
    (v (next-int rng (count v)))))

(defn poisson-sample
  "Sample from Poisson distribution with given lambda using Knuth algorithm."
  [^Random rng lambda]
  (let [l (Math/exp (- lambda))]
    (loop [k 0
           p 1.0]
      (let [p' (* p (next-double rng))]
        (if (> p' l)
          (recur (inc k) p')
          k)))))

;; ---------------------------------------------------------------------------
;; Timestamp generation
;; ---------------------------------------------------------------------------

(def ^:private ts-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn format-ts
  "Format a LocalDateTime as an ISO-like timestamp string."
  [^LocalDateTime ldt]
  (.format ldt ts-formatter))

(defn days-ago
  "Return a LocalDate that is `n` days before today."
  ^LocalDate [n]
  (.minusDays (LocalDate/now) (long n)))

(defn generate-work-hour
  "Generate a random hour weighted around work hours (9-17) using gaussian distribution.
   Also handles nightly builds (2am) when trigger is schedule."
  [^Random rng trigger-type]
  (if (= trigger-type "schedule")
    ;; Nightly builds at ~2am with small jitter
    (+ 2 (next-int rng 1))
    ;; Gaussian centered on 13:00, sigma 3 — clamp to 0-23
    (let [hour (+ 13.0 (* 3.0 (next-gaussian rng)))]
      (-> hour (max 0.0) (min 23.9) int))))

(defn generate-build-timestamp
  "Generate a timestamp for a build on a given date with realistic work hours."
  [^Random rng ^LocalDate date trigger-type]
  (let [hour   (generate-work-hour rng trigger-type)
        minute (next-int rng 60)
        second (next-int rng 60)]
    (.atTime date hour minute second)))

(defn generate-stage-duration-seconds
  "Generate a realistic stage duration in seconds based on stage name."
  [^Random rng stage-name]
  (let [base (case stage-name
               "Checkout"          (+ 3  (next-int rng 8))
               "Build"             (+ 30 (next-int rng 180))
               "Test"              (+ 20 (next-int rng 300))
               "Install"           (+ 10 (next-int rng 60))
               "Install Deps"      (+ 15 (next-int rng 90))
               "Lint"              (+ 5  (next-int rng 30))
               "Security Scan"     (+ 15 (next-int rng 120))
               "Docker Build"      (+ 30 (next-int rng 120))
               "Deploy Staging"    (+ 20 (next-int rng 60))
               "Deploy Prod"       (+ 30 (next-int rng 90))
               "Deploy"            (+ 15 (next-int rng 60))
               "Train Validation"  (+ 60 (next-int rng 600))
               "Archive"           (+ 30 (next-int rng 120))
               "Upload TestFlight" (+ 45 (next-int rng 180))
               "Init"              (+ 5  (next-int rng 15))
               "Validate"          (+ 3  (next-int rng 10))
               "Plan"              (+ 10 (next-int rng 30))
               "Apply"             (+ 20 (next-int rng 120))
               (+ 10 (next-int rng 60)))]
    base))

;; ---------------------------------------------------------------------------
;; Build status / failure determination
;; ---------------------------------------------------------------------------

(defn in-flaky-period?
  "Check if a given day offset falls within a flaky period for the job."
  [job-name day-offset]
  (some (fn [[jn start end _drop]]
          (and (= jn job-name)
               (<= start day-offset end)))
        data/flaky-periods))

(defn get-flaky-drop
  "Get the success rate drop for a job in a flaky period."
  [job-name day-offset]
  (some (fn [[jn start end drop]]
          (when (and (= jn job-name)
                     (<= start day-offset end))
            drop))
        data/flaky-periods))

(defn effective-success-rate
  "Get the effective success rate for a job, accounting for flaky periods."
  [job day-offset]
  (let [base (:success-rate job)
        drop (get-flaky-drop (:name job) day-offset)]
    (if drop
      (max 0.1 (- base drop))
      base)))

(defn build-succeeds?
  "Determine if a build succeeds, using effective success rate."
  [^Random rng job day-offset]
  (< (next-double rng) (effective-success-rate job day-offset)))

(defn pick-failure-stage
  "Pick which stage fails for a failed build, weighted by fail-stage-weights."
  [^Random rng job]
  (let [weights (seq (:fail-stage-weights job))]
    (weighted-choice rng (mapv (fn [[k v]] [k v]) weights))))

;; ---------------------------------------------------------------------------
;; Git metadata generation
;; ---------------------------------------------------------------------------

(defn generate-sha
  "Generate a plausible 40-char hex SHA from the RNG."
  [^Random rng]
  (let [^StringBuilder sb (StringBuilder. 40)]
    (dotimes [_ 40]
      (.append sb (char (nth "0123456789abcdef" (next-int rng 16)))))
    (str sb)))

(defn generate-branch
  "Pick a random branch from weighted distribution."
  [^Random rng]
  (weighted-choice rng data/branch-weights))

(defn generate-commit-message
  "Generate a conventional commit message."
  [^Random rng]
  (let [prefix  (weighted-choice rng data/commit-prefixes)
        subjects (get data/commit-subjects prefix ["update code"])
        subject (pick rng subjects)]
    (str prefix ": " subject)))

(defn generate-git-author
  "Pick a random developer username as git author."
  [^Random rng]
  (pick rng (concat data/developer-usernames data/admin-usernames)))

(defn generate-git-info
  "Generate complete git metadata for a build."
  [^Random rng]
  (let [sha (generate-sha rng)]
    {:branch       (generate-branch rng)
     :commit       sha
     :commit-short (subs sha 0 7)
     :author       (generate-git-author rng)
     :message      (generate-commit-message rng)}))

;; ---------------------------------------------------------------------------
;; Trigger generation
;; ---------------------------------------------------------------------------

(defn generate-trigger-type
  "Pick a trigger type from weighted distribution."
  [^Random rng]
  (weighted-choice rng data/trigger-weights))

;; ---------------------------------------------------------------------------
;; Daily build count
;; ---------------------------------------------------------------------------

(defn builds-for-day
  "Generate the number of builds for a job on a given day.
   Weekdays get full rate, weekends get 20% rate."
  [^Random rng job ^LocalDate date]
  (let [dow       (.getValue ^DayOfWeek (.getDayOfWeek date))
        weekend?  (>= dow 6)
        lambda    (if weekend?
                    (* (:builds-per-day job) 0.2)
                    (:builds-per-day job))]
    (poisson-sample rng lambda)))

;; ---------------------------------------------------------------------------
;; Webhook event generation
;; ---------------------------------------------------------------------------

(defn generate-webhook-event
  "Generate webhook event data for a build."
  [^Random rng git-info job-name]
  (let [provider "github"
        ^String branch (:branch git-info)
        event-type (if (and branch
                            (or (.startsWith branch "feature/")
                                (.startsWith branch "fix/")))
                     "pull_request"
                     "push")]
    {:provider        provider
     :event-type      event-type
     :repo-url        (str "https://github.com/acme/" job-name)
     :repo-name       (str "acme/" job-name)
     :branch          (:branch git-info)
     :commit-sha      (:commit git-info)
     :signature-valid  1
     :status          "processed"
     :matched-jobs    1
     :triggered-builds 1
     :payload-size    (+ 2000 (next-int rng 8000))
     :processing-ms   (+ 5 (next-int rng 50))}))

;; ---------------------------------------------------------------------------
;; Approval decision generation
;; ---------------------------------------------------------------------------

(defn generate-approval-decision
  "Generate an approval decision: 80% approved, 10% pending, 10% rejected."
  [^Random rng]
  (let [roll (next-double rng)]
    (cond
      (< roll 0.80) :approved
      (< roll 0.90) :pending
      :else         :rejected)))
