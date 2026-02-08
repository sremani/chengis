(ns chengis.plugin.builtin.gitlab-status
  "GitLab commit status reporter plugin.
   Reports build status to GitLab using the Commits API:
   POST /api/v4/projects/{id}/statuses/{sha}"
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import [java.net URLEncoder]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- extract-project-path
  "Extract project path from a GitLab repository URL.
   Returns URL-encoded project path for API use.
   Handles: https://gitlab.com/group/project.git, git@gitlab.com:group/project.git"
  [repo-url]
  (when repo-url
    (when-let [[_ path] (re-find #"gitlab[^:/]*[:/](.+?)(?:\.git)?$" repo-url)]
      (URLEncoder/encode path "UTF-8"))))

(defn- map-status
  "Map generic SCM status to GitLab pipeline status values.
   GitLab accepts: pending, running, success, failed, canceled"
  [status]
  (case status
    "success" "success"
    "failure" "failed"
    "error"   "failed"
    "pending" "pending"
    "pending"))

;; ---------------------------------------------------------------------------
;; GitLab Status Reporter
;; ---------------------------------------------------------------------------

(defrecord GitLabStatusReporter []
  proto/ScmStatusReporter
  (report-status [_this build-info config]
    (let [token (or (:token config) (System/getenv "GITLAB_TOKEN"))
          base-url (or (:base-url config) "https://gitlab.com")]
      (if (str/blank? token)
        (do
          (log/debug "GitLab status report skipped: no token configured")
          {:status :skipped :details "No GitLab token configured"})
        (let [project-path (extract-project-path (:repo-url build-info))
              sha (:commit-sha build-info)]
          (if (nil? project-path)
            (do
              (log/warn "Could not extract project path from" (:repo-url build-info))
              {:status :failed :details "Could not parse repo URL"})
            (let [url (str base-url "/api/v4/projects/" project-path "/statuses/" sha)
                  body {:state (map-status (:status build-info))
                        :target_url (:build-url build-info)
                        :description (or (:description build-info) "Chengis CI build")
                        :name (or (:context build-info) "chengis/build")}
                  resp @(http/post url
                          {:headers {"PRIVATE-TOKEN" token
                                     "Content-Type" "application/json"}
                           :body (json/write-str body)
                           :timeout 15000})]
              (if (and (:status resp) (< (:status resp) 300))
                (do
                  (log/info "GitLab status reported:" (:state body) "for" project-path "@" (subs sha 0 (min 8 (count sha))))
                  {:status :sent :details (str "GitLab API: " (:status resp))})
                (do
                  (log/warn "GitLab status report failed:" (:status resp)
                            (when (:body resp) (subs (:body resp) 0 (min 200 (count (:body resp))))))
                  {:status :failed :details (str "HTTP " (:status resp))})))))))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the GitLab status reporter plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "gitlab-status" "0.1.0" "GitLab commit status reporter"
      :provides #{:status-reporter}))
  (registry/register-status-reporter! :gitlab (->GitLabStatusReporter)))
