(ns chengis.plugin.builtin.gitea-status
  "Gitea commit status reporter plugin.
   Reports build status to Gitea using the Commit Statuses API:
   POST /api/v1/repos/{owner}/{repo}/statuses/{sha}"
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
  "Extract owner/repo from a Gitea repository URL.
   Handles: https://gitea.example.com/owner/repo.git"
  [repo-url]
  (when repo-url
    (when-let [[_ owner repo] (re-find #"[:/]([^/]+)/([^/.]+?)(?:\.git)?$" repo-url)]
      {:owner owner :repo repo})))

(defn- map-status
  "Map generic SCM status to Gitea API values.
   Gitea accepts: error, failure, pending, success, warning"
  [status]
  (case status
    "success" "success"
    "failure" "failure"
    "error"   "error"
    "pending" "pending"
    "pending"))

;; ---------------------------------------------------------------------------
;; Gitea Status Reporter
;; ---------------------------------------------------------------------------

(defrecord GiteaStatusReporter []
  proto/ScmStatusReporter
  (report-status [_this build-info config]
    (let [token (or (:token config) (System/getenv "GITEA_TOKEN"))
          base-url (:base-url config)]
      (cond
        (str/blank? token)
        (do (log/debug "Gitea status report skipped: no token configured")
            {:status :skipped :details "No Gitea token configured"})

        (str/blank? base-url)
        (do (log/debug "Gitea status report skipped: no base URL configured")
            {:status :skipped :details "No Gitea base URL configured"})

        :else
        (let [{:keys [owner repo]} (extract-owner-repo (:repo-url build-info))
              sha (:commit-sha build-info)]
          (if (or (nil? owner) (nil? repo))
            (do (log/warn "Could not extract owner/repo from" (:repo-url build-info))
                {:status :failed :details "Could not parse repo URL"})
            (let [url (str (str/replace base-url #"/+$" "")
                           "/api/v1/repos/" owner "/" repo "/statuses/" sha)
                  body {:state (map-status (:status build-info))
                        :target_url (:build-url build-info)
                        :description (or (:description build-info) "Chengis CI build")
                        :context (or (:context build-info) "chengis/build")}
                  resp @(http/post url
                          {:headers {"Authorization" (str "token " token)
                                     "Accept" "application/json"
                                     "Content-Type" "application/json"}
                           :body (json/write-str body)
                           :timeout 15000})]
              (if (and (:status resp) (< (:status resp) 300))
                (do (log/info "Gitea status reported:" (:state body)
                              "for" owner "/" repo "@" (subs sha 0 (min 8 (count sha))))
                    {:status :sent :details (str "Gitea API: " (:status resp))})
                (do (log/warn "Gitea status report failed:" (:status resp))
                    {:status :failed :details (str "HTTP " (:status resp))})))))))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the Gitea status reporter plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "gitea-status" "0.1.0" "Gitea commit status reporter"
      :provides #{:status-reporter}))
  (registry/register-status-reporter! :gitea (->GiteaStatusReporter)))
