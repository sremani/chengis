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
   Uses proper host matching to avoid false positives (e.g., evil-github.com).
   Returns :github, :gitlab, :gitea, :bitbucket, or nil.
   Gitea detection requires :scm :gitea :base-url in config (self-hosted)."
  ([repo-url] (detect-provider repo-url nil))
  ([repo-url config]
   (when (seq repo-url)
     (try
       (let [host (str/lower-case (.getHost (java.net.URI. repo-url)))
             gitea-base (get-in config [:scm :gitea :base-url])]
         (cond
           (or (= host "github.com") (str/ends-with? host ".github.com")) :github
           (or (= host "gitlab.com") (str/ends-with? host ".gitlab.com")) :gitlab
           (or (= host "bitbucket.org") (str/ends-with? host ".bitbucket.org")) :bitbucket
           ;; Gitea is self-hosted — match against configured base URL
           (and gitea-base
                (try
                  (let [gitea-host (str/lower-case (.getHost (java.net.URI. gitea-base)))]
                    (= host gitea-host))
                  (catch Exception _ false))) :gitea
           :else nil))
       (catch Exception _
         ;; Fallback for non-URL formats (e.g., git@github.com:user/repo.git)
         (let [gitea-base (get-in config [:scm :gitea :base-url])]
           (cond
             (str/includes? repo-url "github.com") :github
             (str/includes? repo-url "gitlab.com") :gitlab
             (str/includes? repo-url "bitbucket.org") :bitbucket
             ;; Gitea SSH fallback: check if URL contains the gitea host
             (and gitea-base
                  (try
                    (let [gitea-host (.getHost (java.net.URI. gitea-base))]
                      (str/includes? repo-url gitea-host))
                    (catch Exception _ false))) :gitea
             :else nil)))))))


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
      (let [provider (detect-provider repo-url config)]
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
