(ns chengis.engine.pr-checks
  "PR/MR status check enforcement engine.
   Integrates with the build lifecycle to:
   1. Report check status to SCM (GitHub/GitLab) on build start/complete
   2. Track required check results per commit
   3. Evaluate whether all required checks pass for merge readiness"
  (:require [chengis.db.pr-check-store :as pr-store]
            [chengis.engine.scm-status :as scm-status]
            [chengis.feature-flags :as feature-flags]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Check lifecycle
;; ---------------------------------------------------------------------------

(defn report-check-started!
  "Record and report a check as started (pending) for a build.
   Called at build start when PR status checks are enabled."
  [system build-info]
  (let [ds (:db system)
        config (:config system)
        {:keys [build-id job-id org-id commit-sha repo-url]} build-info]
    (when (and (feature-flags/enabled? config :pr-status-checks)
               (seq commit-sha))
      (let [checks (pr-store/list-checks ds job-id :org-id org-id)
            base-url (or (get-in config [:server :base-url])
                         (str "http://localhost:" (get-in config [:server :port] 8080)))
            build-url (str base-url "/builds/" build-id)]
        (doseq [check checks]
          ;; Record to DB
          (pr-store/record-check-result! ds
            {:build-id build-id
             :job-id job-id
             :org-id (or org-id "default-org")
             :check-name (:check-name check)
             :status :pending
             :target-url build-url
             :description (or (:description check) "Build in progress")
             :commit-sha commit-sha
             :repo-url repo-url})
          ;; Report to SCM
          (scm-status/report! system
            {:commit-sha commit-sha
             :repo-url repo-url
             :build-id build-id
             :build-number nil
             :job-id job-id}
            :running
            (str (:check-name check) " — pending")))
        (log/info "Reported" (count checks) "PR checks as pending for build" build-id)))))

(defn report-check-completed!
  "Record and report a check as completed for a build.
   Called when a build finishes (success/failure/error)."
  [system build-info build-status]
  (let [ds (:db system)
        config (:config system)
        {:keys [build-id job-id org-id commit-sha repo-url]} build-info]
    (when (and (feature-flags/enabled? config :pr-status-checks)
               (seq commit-sha))
      (let [checks (pr-store/list-checks ds job-id :org-id org-id)
            base-url (or (get-in config [:server :base-url])
                         (str "http://localhost:" (get-in config [:server :port] 8080)))
            build-url (str base-url "/builds/" build-id)
            status-name (name build-status)]
        (doseq [check checks]
          ;; Record to DB
          (pr-store/record-check-result! ds
            {:build-id build-id
             :job-id job-id
             :org-id (or org-id "default-org")
             :check-name (:check-name check)
             :status build-status
             :target-url build-url
             :description (str (:check-name check) " — " status-name)
             :commit-sha commit-sha
             :repo-url repo-url})
          ;; Report to SCM
          (scm-status/report! system
            {:commit-sha commit-sha
             :repo-url repo-url
             :build-id build-id
             :build-number nil
             :job-id job-id}
            build-status
            (str (:check-name check) " — " status-name)))
        (log/info "Reported" (count checks) "PR checks as" status-name "for build" build-id)))))

;; ---------------------------------------------------------------------------
;; Check evaluation
;; ---------------------------------------------------------------------------

(defn evaluate-merge-readiness
  "Evaluate whether a commit is ready to merge based on all required checks.
   Returns {:ready? bool :checks [...] :summary str}."
  [ds job-id build-id]
  (let [{:keys [passing? total passed checks]} (pr-store/all-required-checks-passing? ds job-id build-id)]
    {:ready? passing?
     :total total
     :passed passed
     :checks checks
     :summary (if passing?
                (str "All " total " required checks passed")
                (str passed "/" total " required checks passed"))}))

(defn get-commit-status-summary
  "Get a summary of all check results for a commit across builds.
   Useful for PR status display."
  [ds commit-sha & {:keys [org-id]}]
  (let [results (pr-store/get-commit-check-results ds commit-sha :org-id org-id)
        by-check (group-by :check-name results)]
    {:commit-sha commit-sha
     :checks (mapv (fn [[check-name results]]
                     (let [latest (first results)]  ;; sorted desc by created-at
                       {:check-name check-name
                        :status (:status latest)
                        :build-id (:build-id latest)
                        :completed-at (:completed-at latest)}))
                   by-check)
     :all-passing? (every? #(= :success (:status (first (val %)))) by-check)}))
