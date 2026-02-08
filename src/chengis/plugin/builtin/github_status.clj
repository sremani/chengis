(ns chengis.plugin.builtin.github-status
  "GitHub commit status reporter plugin.
   Reports build status to GitHub using the Statuses API:
   POST /repos/{owner}/{repo}/statuses/{sha}"
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- extract-owner-repo
  "Extract owner/repo from a GitHub repository URL.
   Handles: https://github.com/owner/repo.git, git@github.com:owner/repo.git"
  [repo-url]
  (when repo-url
    (or
      ;; HTTPS format: https://github.com/owner/repo.git
      (when-let [[_ owner repo] (re-find #"github\.com[:/]([^/]+)/([^/.]+)" repo-url)]
        {:owner owner :repo repo})
      ;; SSH format: git@github.com:owner/repo.git
      (when-let [[_ owner repo] (re-find #"github\.com:([^/]+)/([^/.]+)" repo-url)]
        {:owner owner :repo repo}))))

(defn- map-status
  "Map generic SCM status to GitHub status API values.
   GitHub accepts: error, failure, pending, success"
  [status]
  (case status
    "success" "success"
    "failure" "failure"
    "error"   "error"
    "pending" "pending"
    "pending"))

;; ---------------------------------------------------------------------------
;; GitHub Status Reporter
;; ---------------------------------------------------------------------------

(defrecord GitHubStatusReporter []
  proto/ScmStatusReporter
  (report-status [_this build-info config]
    (let [token (or (:token config) (System/getenv "GITHUB_TOKEN"))
          base-url (or (:base-url config) "https://api.github.com")]
      (if (str/blank? token)
        (do
          (log/debug "GitHub status report skipped: no token configured")
          {:status :skipped :details "No GitHub token configured"})
        (let [{:keys [owner repo]} (extract-owner-repo (:repo-url build-info))
              sha (:commit-sha build-info)]
          (if (or (nil? owner) (nil? repo))
            (do
              (log/warn "Could not extract owner/repo from" (:repo-url build-info))
              {:status :failed :details "Could not parse repo URL"})
            (let [url (str base-url "/repos/" owner "/" repo "/statuses/" sha)
                  body {:state (map-status (:status build-info))
                        :target_url (:build-url build-info)
                        :description (or (:description build-info) "Chengis CI build")
                        :context (or (:context build-info) "chengis/build")}
                  resp @(http/post url
                          {:headers {"Authorization" (str "Bearer " token)
                                     "Accept" "application/vnd.github+json"
                                     "Content-Type" "application/json"
                                     "X-GitHub-Api-Version" "2022-11-28"}
                           :body (json/write-str body)
                           :timeout 15000})]
              (if (and (:status resp) (< (:status resp) 300))
                (do
                  (log/info "GitHub status reported:" (:state body) "for" owner "/" repo "@" (subs sha 0 (min 8 (count sha))))
                  {:status :sent :details (str "GitHub API: " (:status resp))})
                (do
                  (log/warn "GitHub status report failed:" (:status resp)
                            (when (:body resp) (subs (:body resp) 0 (min 200 (count (:body resp))))))
                  {:status :failed :details (str "HTTP " (:status resp))})))))))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the GitHub status reporter plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "github-status" "0.1.0" "GitHub commit status reporter"
      :provides #{:status-reporter}))
  (registry/register-status-reporter! :github (->GitHubStatusReporter)))
