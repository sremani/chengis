(ns chengis.plugin.builtin.bitbucket-status
  "Bitbucket Cloud commit status reporter plugin.
   Reports build status to Bitbucket using the Build Status API:
   POST /2.0/repositories/{workspace}/{repo_slug}/commit/{sha}/statuses/build"
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- extract-workspace-repo
  "Extract workspace/repo from a Bitbucket repository URL.
   Handles: https://bitbucket.org/workspace/repo.git"
  [repo-url]
  (when repo-url
    (or
      ;; HTTPS format
      (when-let [[_ workspace repo] (re-find #"bitbucket\.org[:/]([^/]+)/([^/.]+)" repo-url)]
        {:workspace workspace :repo repo})
      ;; SSH format
      (when-let [[_ workspace repo] (re-find #"bitbucket\.org:([^/]+)/([^/.]+)" repo-url)]
        {:workspace workspace :repo repo}))))

(defn- basic-auth-header
  "Create a Basic Authentication header from username and app password."
  [username app-password]
  (let [creds (str username ":" app-password)
        encoded (.encodeToString (Base64/getEncoder) (.getBytes creds "UTF-8"))]
    (str "Basic " encoded)))

(defn- map-status
  "Map generic SCM status to Bitbucket API values.
   Bitbucket accepts: SUCCESSFUL, FAILED, INPROGRESS, STOPPED"
  [status]
  (case status
    "success" "SUCCESSFUL"
    "failure" "FAILED"
    "error"   "FAILED"
    "pending" "INPROGRESS"
    "INPROGRESS"))

;; ---------------------------------------------------------------------------
;; Bitbucket Status Reporter
;; ---------------------------------------------------------------------------

(defrecord BitbucketStatusReporter []
  proto/ScmStatusReporter
  (report-status [_this build-info config]
    (let [username (or (:username config) (System/getenv "BITBUCKET_USERNAME"))
          app-password (or (:app-password config) (System/getenv "BITBUCKET_APP_PASSWORD"))
          base-url (or (:base-url config) "https://api.bitbucket.org/2.0")]
      (if (or (str/blank? username) (str/blank? app-password))
        (do (log/debug "Bitbucket status report skipped: no credentials configured")
            {:status :skipped :details "No Bitbucket credentials configured"})
        (let [{:keys [workspace repo]} (extract-workspace-repo (:repo-url build-info))
              sha (:commit-sha build-info)]
          (if (or (nil? workspace) (nil? repo))
            (do (log/warn "Could not extract workspace/repo from" (:repo-url build-info))
                {:status :failed :details "Could not parse Bitbucket repo URL"})
            (let [url (str base-url "/repositories/" workspace "/" repo
                           "/commit/" sha "/statuses/build")
                  body {:state (map-status (:status build-info))
                        :key (or (:context build-info) "chengis-build")
                        :name (or (:context build-info) "chengis/build")
                        :url (:build-url build-info)
                        :description (or (:description build-info) "Chengis CI build")}
                  resp @(http/post url
                          {:headers {"Authorization" (basic-auth-header username app-password)
                                     "Content-Type" "application/json"}
                           :body (json/write-str body)
                           :timeout 15000})]
              (if (and (:status resp) (< (:status resp) 300))
                (do (log/info "Bitbucket status reported:" (:state body)
                              "for" workspace "/" repo "@" (subs sha 0 (min 8 (count sha))))
                    {:status :sent :details (str "Bitbucket API: " (:status resp))})
                (do (log/warn "Bitbucket status report failed:" (:status resp))
                    {:status :failed :details (str "HTTP " (:status resp))})))))))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the Bitbucket status reporter plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "bitbucket-status" "0.1.0" "Bitbucket Cloud commit status reporter"
      :provides #{:status-reporter}))
  (registry/register-status-reporter! :bitbucket (->BitbucketStatusReporter)))
