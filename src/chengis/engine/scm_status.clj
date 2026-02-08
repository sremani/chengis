(ns chengis.engine.scm-status
  "SCM status reporting — dispatches build status to registered SCM providers.
   Determines the provider from the build's repo-url and calls the appropriate
   status reporter plugin."
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [chengis.metrics :as metrics]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Provider detection
;; ---------------------------------------------------------------------------

(defn detect-provider
  "Detect the SCM provider from a repository URL.
   Returns :github, :gitlab, or nil."
  [repo-url]
  (when (seq repo-url)
    (cond
      (str/includes? repo-url "github.com")  :github
      (str/includes? repo-url "gitlab.com")  :gitlab
      (str/includes? repo-url "github")      :github
      (str/includes? repo-url "gitlab")      :gitlab
      :else nil)))

;; ---------------------------------------------------------------------------
;; Status mapping
;; ---------------------------------------------------------------------------

(defn build-status->scm-status
  "Map internal build status keywords to a generic SCM status string.
   Each provider plugin can further translate these."
  [build-status]
  (case (keyword build-status)
    :success           "success"
    :failure           "failure"
    :aborted           "error"
    :running           "pending"
    :queued            "pending"
    :waiting-approval  "pending"
    "pending"))

;; ---------------------------------------------------------------------------
;; Report dispatch
;; ---------------------------------------------------------------------------

(defn report!
  "Report build status to the appropriate SCM provider.
   No-op if: no commit-sha, no repo-url, no provider detected, or no reporter registered.

   Arguments:
     system       - system map with :config, :metrics
     build-info   - map with :commit-sha :repo-url :build-id :build-number :job-id
     build-status - keyword status (:running, :success, :failure, etc.)
     description  - human-readable description string"
  [system build-info build-status description]
  (let [commit-sha (:commit-sha build-info)
        repo-url   (:repo-url build-info)
        config     (:config system)
        registry*  (:metrics system)]
    (when (and (seq commit-sha) (seq repo-url))
      (let [provider (detect-provider repo-url)]
        (if-not provider
          (log/debug "No SCM provider detected for" repo-url)
          (let [reporter (registry/get-status-reporter provider)]
            (if-not reporter
              (log/debug "No status reporter registered for" provider)
              (let [base-url   (or (get-in config [:server :base-url])
                                   (str "http://localhost:" (get-in config [:server :port] 8080)))
                    build-url  (str base-url "/builds/" (:build-id build-info))
                    scm-config (get-in config [:scm (keyword provider)] {})
                    info       {:commit-sha  commit-sha
                                :repo-url    repo-url
                                :status      (build-status->scm-status build-status)
                                :build-url   build-url
                                :description description
                                :context     (or (:context scm-config) "chengis/build")
                                :build-id    (:build-id build-info)
                                :job-id      (:job-id build-info)}]
                (try
                  (log/info "Reporting" (name build-status) "status to" (name provider)
                            "for commit" (subs commit-sha 0 (min 8 (count commit-sha))))
                  (let [result (proto/report-status reporter info scm-config)]
                    (try (metrics/record-scm-status-report! registry*
                           (name provider) (name (:status result)))
                         (catch Exception _))
                    result)
                  (catch Exception e
                    (log/warn "SCM status report failed for" (name provider) ":" (.getMessage e))
                    (try (metrics/record-scm-status-report! registry* (name provider) "error")
                         (catch Exception _))
                    {:status :failed :details (.getMessage e)}))))))))))
