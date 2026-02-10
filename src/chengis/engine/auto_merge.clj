(ns chengis.engine.auto-merge
  "Auto-merge engine for PRs/MRs when all required checks pass.
   Monitors build completions and triggers merge via SCM provider APIs
   when a job is configured for auto-merge and all required checks are passing.
   Gated by feature flag :auto-merge."
  (:require [chengis.db.pr-check-store :as pr-store]
            [chengis.engine.scm-status :as scm-status]
            [chengis.feature-flags :as feature-flags]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- basic-auth-header
  "Create a Basic Authentication header from username and password."
  [username password]
  (let [creds (str username ":" password)
        encoded (.encodeToString (Base64/getEncoder) (.getBytes creds "UTF-8"))]
    (str "Basic " encoded)))

(defn- extract-pr-number
  "Extract PR/MR number from a webhook payload or build-info."
  [build-info]
  (or (:pr-number build-info)
      (:merge-request-number build-info)))

(defn- extract-owner-repo
  "Extract owner/repo from a repository URL.
   Handles HTTPS and SSH formats for GitHub, GitLab, Bitbucket, Gitea."
  [repo-url]
  (when repo-url
    (or
      (when-let [[_ owner repo] (re-find #"[:/]([^/]+)/([^/.]+?)(?:\.git)?$" repo-url)]
        {:owner owner :repo repo})
      nil)))

;; ---------------------------------------------------------------------------
;; Provider-specific merge APIs
;; ---------------------------------------------------------------------------

(defn- merge-github-pr!
  "Merge a GitHub pull request via the Merges API.
   PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge"
  [owner repo pr-number config merge-config]
  (let [token (or (:token config) (System/getenv "GITHUB_TOKEN"))
        base-url (or (:base-url config) "https://api.github.com")
        merge-method (or (:merge-method merge-config) "merge")
        url (str base-url "/repos/" owner "/" repo "/pulls/" pr-number "/merge")
        body {:merge_method merge-method}
        resp @(http/put url
                {:headers {"Authorization" (str "Bearer " token)
                           "Accept" "application/vnd.github+json"
                           "Content-Type" "application/json"
                           "X-GitHub-Api-Version" "2022-11-28"}
                 :body (json/write-str body)
                 :timeout 30000})]
    (if (and (:status resp) (< (:status resp) 300))
      (do (log/info "GitHub PR" pr-number "merged successfully for" owner "/" repo)
          {:status :merged :details (str "GitHub API: " (:status resp))})
      (do (log/warn "GitHub PR merge failed:" (:status resp))
          {:status :failed :details (str "HTTP " (:status resp))}))))

(defn- delete-github-branch!
  "Delete a branch on GitHub after merge.
   DELETE /repos/{owner}/{repo}/git/refs/heads/{branch}"
  [owner repo branch config]
  (let [token (or (:token config) (System/getenv "GITHUB_TOKEN"))
        base-url (or (:base-url config) "https://api.github.com")
        url (str base-url "/repos/" owner "/" repo "/git/refs/heads/" branch)
        resp @(http/delete url
                {:headers {"Authorization" (str "Bearer " token)
                           "Accept" "application/vnd.github+json"
                           "X-GitHub-Api-Version" "2022-11-28"}
                 :timeout 15000})]
    (if (and (:status resp) (< (:status resp) 300))
      (log/info "Branch" branch "deleted after merge for" owner "/" repo)
      (log/warn "Branch deletion failed:" (:status resp)))))

(defn- merge-gitlab-mr!
  "Merge a GitLab merge request via the Merge Requests API.
   PUT /api/v4/projects/{project_path}/merge_requests/{mr_iid}/merge"
  [project-path mr-number config merge-config]
  (let [token (or (:token config) (System/getenv "GITLAB_TOKEN"))
        base-url (or (:base-url config) "https://gitlab.com")
        squash? (= "squash" (:merge-method merge-config))
        url (str base-url "/api/v4/projects/"
                 (java.net.URLEncoder/encode project-path "UTF-8")
                 "/merge_requests/" mr-number "/merge")
        body (cond-> {}
               squash? (assoc :squash true)
               (:delete-branch-after merge-config) (assoc :should_remove_source_branch true))
        resp @(http/put url
                {:headers {"PRIVATE-TOKEN" token
                           "Content-Type" "application/json"}
                 :body (json/write-str body)
                 :timeout 30000})]
    (if (and (:status resp) (< (:status resp) 300))
      (do (log/info "GitLab MR" mr-number "merged successfully for" project-path)
          {:status :merged :details (str "GitLab API: " (:status resp))})
      (do (log/warn "GitLab MR merge failed:" (:status resp))
          {:status :failed :details (str "HTTP " (:status resp))}))))

(defn- merge-bitbucket-pr!
  "Merge a Bitbucket pull request via the Pull Requests API.
   POST /2.0/repositories/{workspace}/{repo}/pullrequests/{id}/merge"
  [workspace repo pr-number config merge-config]
  (let [username (or (:username config) (System/getenv "BITBUCKET_USERNAME"))
        app-password (or (:app-password config) (System/getenv "BITBUCKET_APP_PASSWORD"))
        base-url (or (:base-url config) "https://api.bitbucket.org/2.0")
        merge-strategy (case (:merge-method merge-config)
                         "squash" "squash"
                         "rebase" "fast_forward"
                         "merge_commit")
        url (str base-url "/repositories/" workspace "/" repo
                 "/pullrequests/" pr-number "/merge")
        body {:type "pullrequest"
              :merge_strategy merge-strategy
              :close_source_branch (boolean (:delete-branch-after merge-config))}
        resp @(http/post url
                {:headers {"Authorization" (basic-auth-header username app-password)
                           "Content-Type" "application/json"}
                 :body (json/write-str body)
                 :timeout 30000})]
    (if (and (:status resp) (< (:status resp) 300))
      (do (log/info "Bitbucket PR" pr-number "merged for" workspace "/" repo)
          {:status :merged :details (str "Bitbucket API: " (:status resp))})
      (do (log/warn "Bitbucket PR merge failed:" (:status resp))
          {:status :failed :details (str "HTTP " (:status resp))}))))

(defn- merge-gitea-pr!
  "Merge a Gitea pull request via the Pull Request API.
   POST /api/v1/repos/{owner}/{repo}/pulls/{index}/merge"
  [owner repo pr-number config merge-config]
  (let [token (or (:token config) (System/getenv "GITEA_TOKEN"))
        base-url (:base-url config)
        merge-method (case (:merge-method merge-config)
                       "squash" "squash"
                       "rebase" "rebase"
                       "merge")
        url (str (str/replace base-url #"/+$" "")
                 "/api/v1/repos/" owner "/" repo "/pulls/" pr-number "/merge")
        body {:Do merge-method
              :delete_branch_after_merge (boolean (:delete-branch-after merge-config))}
        resp @(http/post url
                {:headers {"Authorization" (str "token " token)
                           "Accept" "application/json"
                           "Content-Type" "application/json"}
                 :body (json/write-str body)
                 :timeout 30000})]
    (if (and (:status resp) (< (:status resp) 300))
      (do (log/info "Gitea PR" pr-number "merged for" owner "/" repo)
          {:status :merged :details (str "Gitea API: " (:status resp))})
      (do (log/warn "Gitea PR merge failed:" (:status resp))
          {:status :failed :details (str "HTTP " (:status resp))}))))

;; ---------------------------------------------------------------------------
;; Provider dispatch
;; ---------------------------------------------------------------------------

(defn- detect-provider
  "Detect SCM provider from repo URL. Delegates to scm-status/detect-provider
   for consistent, secure host-based matching."
  [repo-url config]
  (scm-status/detect-provider repo-url config))

(defn- extract-project-path
  "Extract GitLab project path from repo URL."
  [repo-url]
  (when repo-url
    (when-let [[_ path] (re-find #"gitlab[^:/]*[:/](.+?)(?:\.git)?$" repo-url)]
      path)))

;; ---------------------------------------------------------------------------
;; Auto-merge orchestration
;; ---------------------------------------------------------------------------

(defn should-auto-merge?
  "Check if a build should trigger auto-merge evaluation:
   1. Feature flag :auto-merge is enabled
   2. Job has auto-merge enabled
   3. Build has a PR/MR number
   Note: actual required-checks evaluation is done in evaluate-and-merge!."
  [system build-info job]
  (and (feature-flags/enabled? (:config system) :auto-merge)
       (:auto-merge-enabled job)
       (some? (extract-pr-number build-info))))

(defn evaluate-and-merge!
  "Evaluate required checks for a build and merge the PR if all pass.
   Returns a result map with :status (:merged, :not-ready, :failed, :skipped, :no-pr).

   Arguments:
     system     - system map with :db, :config
     build-info - map with :build-id, :job-id, :repo-url, :pr-number, etc.
     job        - job record with :auto-merge-enabled"
  [system build-info job]
  (if-not (feature-flags/enabled? (:config system) :auto-merge)
    {:status :skipped :details "Auto-merge feature flag disabled"}
    (if-not (:auto-merge-enabled job)
      {:status :skipped :details "Auto-merge not enabled for this job"}
      (let [pr-number (extract-pr-number build-info)]
        (if-not pr-number
          {:status :no-pr :details "No PR/MR number in build info"}
          (let [ds (:db system)
                config (:config system)
                check-result (pr-store/all-required-checks-passing? ds (:job-id build-info) (:build-id build-info))]
            (if-not (:passing? check-result)
              (do (log/info "Auto-merge skipped: not all required checks passing"
                            (str (:passed check-result) "/" (:total check-result)))
                  {:status :not-ready
                   :details (str "Checks: " (:passed check-result) "/" (:total check-result) " passing")})
              ;; All checks pass — perform the merge
              (let [repo-url (:repo-url build-info)
                    provider (detect-provider repo-url config)
                    merge-config (:auto-merge config)
                    {:keys [owner repo]} (extract-owner-repo repo-url)]
                (if-not provider
                  {:status :failed :details "Could not detect SCM provider"}
                  (try
                    (log/info "Auto-merging PR" pr-number "on" (name provider)
                              "for" owner "/" repo)
                    (let [scm-config (get-in config [:scm (keyword provider)] {})
                          result (case provider
                                   :github (merge-github-pr! owner repo pr-number scm-config merge-config)
                                   :gitlab (merge-gitlab-mr! (extract-project-path repo-url) pr-number scm-config merge-config)
                                   :bitbucket (merge-bitbucket-pr! owner repo pr-number scm-config merge-config)
                                   :gitea (merge-gitea-pr! owner repo pr-number scm-config merge-config)
                                   {:status :failed :details (str "Unsupported provider: " (name provider))})]
                      ;; Delete branch after merge if configured (GitHub only — others handle it in merge body)
                      (when (and (= :merged (:status result))
                                 (:delete-branch-after merge-config)
                                 (= provider :github)
                                 (:source-branch build-info))
                        (delete-github-branch! owner repo (:source-branch build-info) scm-config))
                      result)
                    (catch Exception e
                      (log/warn "Auto-merge failed:" (.getMessage e))
                      {:status :failed :details (.getMessage e)})))))))))))
