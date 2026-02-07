(ns chengis.web.handlers
  (:require [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.db.artifact-store :as artifact-store]
            [chengis.db.notification-store :as notification-store]
            [chengis.db.secret-store :as secret-store]
            [chengis.engine.build-runner :as build-runner]
            [chengis.engine.cleanup :as cleanup]
            [chengis.engine.events :as events]
            [chengis.web.views.dashboard :as v-dashboard]
            [chengis.web.views.jobs :as v-jobs]
            [chengis.web.views.admin :as v-admin]
            [chengis.web.views.builds :as v-builds]
            [chengis.web.views.trigger-form :as v-trigger-form]
            [chengis.web.sse :as sse]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]
            [ring.middleware.anti-forgery :as anti-forgery]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent RejectedExecutionException]))

(defn- csrf-token
  "Get the current CSRF token from the Ring request."
  [_req]
  (force anti-forgery/*anti-forgery-token*))

(defn- html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- not-found [msg]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<h1>404</h1><p>" (escape-html msg) "</p>")})

(defn dashboard-page [system]
  (fn [req]
    (let [ds (:db system)
          jobs (job-store/list-jobs ds)
          builds (build-store/list-builds ds)
          running (count (filter #(= :running (:status %)) builds))
          queued (count (filter #(= :queued (:status %)) builds))
          stats (build-store/get-build-stats ds)
          recent-history (build-store/get-recent-build-history ds 30)]
      (html-response
        (v-dashboard/render {:jobs jobs
                             :builds builds
                             :running-count running
                             :queued-count queued
                             :stats stats
                             :recent-history recent-history
                             :csrf-token (csrf-token req)})))))

(defn jobs-list-page [system]
  (fn [req]
    (let [ds (:db system)
          jobs (job-store/list-jobs ds)]
      (html-response (v-jobs/render-list {:jobs jobs
                                          :csrf-token (csrf-token req)})))))

(defn job-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          job-name (get-in req [:path-params :name])
          job (job-store/get-job ds job-name)]
      (if-not job
        (not-found (str "Job not found: " job-name))
        (let [builds (build-store/list-builds ds (:id job))
              stats (build-store/get-build-stats ds (:id job))
              recent-history (build-store/get-recent-build-history ds (:id job) 30)
              secret-names (concat
                             (secret-store/list-secret-names ds :scope "global")
                             (secret-store/list-secret-names ds :scope (:id job)))]
          (html-response
            (v-jobs/render-detail {:job job :builds builds
                                   :stats stats
                                   :recent-history recent-history
                                   :secret-names (distinct secret-names)
                                   :csrf-token (csrf-token req)})))))))

(defn- extract-params-from-form
  "Extract build parameters from form submission.
   Form fields named 'param_<name>' are extracted as {:name value}."
  [form-params]
  (when (seq form-params)
    (reduce-kv (fn [acc k v]
                 (if (str/starts-with? k "param_")
                   (let [param-name (keyword (subs k 6))]
                     (assoc acc param-name v))
                   acc))
               {}
               form-params)))

(defn trigger-form [system]
  (fn [req]
    (let [ds (:db system)
          job-name (get-in req [:path-params :name])
          job (job-store/get-job ds job-name)]
      (if-not job
        (not-found (str "Job not found: " job-name))
        (let [parameters (or (:parameters job)
                              (get-in job [:pipeline :parameters]))]
          (html-response
            (str (h/html
                   (v-trigger-form/render-trigger-form
                     job-name parameters (csrf-token req))))))))))

(defn trigger-build [system]
  (fn [req]
    (let [ds (:db system)
          job-name (get-in req [:path-params :name])
          job (job-store/get-job ds job-name)
          form-params (:form-params req)
          build-params (extract-params-from-form form-params)]
      (if-not job
        (not-found (str "Job not found: " job-name))
        (let [build-record (build-store/create-build! ds
                             {:job-id (:id job)
                              :trigger-type :manual
                              :parameters build-params})
              build-id (:id build-record)]
          (log/info "Web trigger: build #" (:build-number build-record) "for" job-name
                    "(id:" build-id ")" (when (seq build-params) (str "params:" build-params)))
          ;; Run build on bounded thread pool
          (try
            (.submit build-runner/build-executor
              ^Runnable (fn []
                (try
                  (build-runner/execute-build-for-record!
                    system job build-record
                    {:event-fn events/publish!
                     :parameters build-params})
                  (catch Exception e
                    (log/error e "Build failed:" build-id)
                    (build-store/update-build-status! ds build-id :failure
                      :completed-at (str (java.time.Instant/now)))
                    (events/publish! {:build-id build-id
                                      :event-type :build-completed
                                      :timestamp (str (java.time.Instant/now))
                                      :data {:build-status :failure}})))))
            ;; Redirect to build page
            {:status 303
             :headers {"Location" (str "/builds/" build-id)}}
            (catch RejectedExecutionException _
              {:status 503
               :headers {"Content-Type" "text/html; charset=utf-8"
                         "Retry-After" "30"}
               :body "<h1>503</h1><p>Build queue full. Try again shortly.</p>"})))))))

(defn build-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          build-id (get-in req [:path-params :id])
          build (build-store/get-build ds build-id)]
      (if-not build
        (not-found (str "Build not found: " build-id))
        (let [stages (build-store/get-build-stages ds build-id)
              steps (build-store/get-build-steps ds build-id)
              job (job-store/get-job-by-id ds (:job-id build))
              artifacts (artifact-store/list-artifacts ds build-id)
              notifications (notification-store/list-notifications ds build-id)]
          (html-response
            (v-builds/render-detail {:build build
                                     :stages stages
                                     :steps steps
                                     :job job
                                     :artifacts artifacts
                                     :notifications notifications
                                     :csrf-token (csrf-token req)})))))))

(defn build-log-page [system]
  (fn [req]
    (let [ds (:db system)
          build-id (get-in req [:path-params :id])
          build (build-store/get-build ds build-id)]
      (if-not build
        (not-found (str "Build not found: " build-id))
        (let [steps (build-store/get-build-steps ds build-id)]
          (html-response
            (v-builds/render-log {:build build :steps steps
                                  :csrf-token (csrf-token req)})))))))

(defn cancel-build [system]
  (fn [req]
    (let [build-id (get-in req [:path-params :id])
          cancelled? (build-runner/cancel-build! build-id)]
      (if cancelled?
        (do
          (log/info "Build cancelled via web:" build-id)
          {:status 303
           :headers {"Location" (str "/builds/" build-id)}})
        {:status 404
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body "<p>Build not found or already completed.</p>"}))))

(defn retry-build [system]
  (fn [req]
    (let [ds (:db system)
          build-id (get-in req [:path-params :id])
          build (build-store/get-build ds build-id)]
      (if-not build
        (not-found (str "Build not found: " build-id))
        (let [job (job-store/get-job-by-id ds (:job-id build))
              new-record (build-store/create-build! ds
                           {:job-id (:job-id build)
                            :trigger-type :retry
                            :parameters (:parameters build)
                            :parent-build-id build-id})
              new-id (:id new-record)]
          (log/info "Retry build #" (:build-number new-record) "from" build-id)
          (try
            (.submit build-runner/build-executor
              ^Runnable (fn []
                (try
                  (build-runner/execute-build-for-record!
                    system job new-record
                    {:event-fn events/publish!
                     :parameters (:parameters build)})
                  (catch Exception e
                    (log/error e "Retry build failed:" new-id)
                    (build-store/update-build-status! ds new-id :failure
                      :completed-at (str (java.time.Instant/now)))
                    (events/publish! {:build-id new-id
                                      :event-type :build-completed
                                      :timestamp (str (java.time.Instant/now))
                                      :data {:build-status :failure}})))))
            {:status 303
             :headers {"Location" (str "/builds/" new-id)}}
            (catch RejectedExecutionException _
              {:status 503
               :headers {"Content-Type" "text/html; charset=utf-8"
                         "Retry-After" "30"}
               :body "<h1>503</h1><p>Build queue full. Try again shortly.</p>"})))))))

(defn build-events-sse [system]
  (fn [req]
    (let [build-id (get-in req [:path-params :id])]
      ((sse/sse-handler system build-id) req))))

;; --- Secrets ---

(defn create-secret [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          job-name (get-in req [:path-params :name])
          job (job-store/get-job ds job-name)
          params (:form-params req)
          secret-name (get params "secret-name")
          secret-value (get params "secret-value")
          scope (get params "scope" "global")]
      (if (or (str/blank? secret-name) (str/blank? secret-value))
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body "<p>Secret name and value are required.</p>"}
        (let [effective-scope (if (= scope "job")
                                (:id job)
                                "global")]
          (secret-store/set-secret! ds config secret-name secret-value
                                    :scope effective-scope)
          (log/info "Secret created:" secret-name "scope:" effective-scope)
          {:status 303
           :headers {"Location" (str "/jobs/" job-name)}})))))

(defn delete-secret [system]
  (fn [req]
    (let [ds (:db system)
          job-name (get-in req [:path-params :name])
          secret-key (get-in req [:path-params :key])
          scope (get-in req [:query-params "scope"] "global")
          job (when (not= scope "global")
                (job-store/get-job ds job-name))
          effective-scope (if (and job (not= scope "global"))
                            (:id job)
                            scope)]
      (secret-store/delete-secret! ds secret-key :scope effective-scope)
      (log/info "Secret deleted:" secret-key "scope:" effective-scope)
      {:status 303
       :headers {"Location" (str "/jobs/" job-name)}})))

;; --- Artifacts ---

(defn download-artifact [system]
  (fn [req]
    (let [ds (:db system)
          build-id (get-in req [:path-params :id])
          filename (get-in req [:path-params :filename])
          artifact (artifact-store/get-artifact ds build-id filename)]
      (if-not artifact
        (not-found (str "Artifact not found: " filename))
        (let [file (io/file (:path artifact))]
          (if (.exists file)
            {:status 200
             :headers {"Content-Type" (or (:content-type artifact) "application/octet-stream")
                       "Content-Disposition" (str "attachment; filename=\"" filename "\"")}
             :body file}
            (not-found (str "Artifact file missing from disk: " filename))))))))

;; --- Admin ---

(defn admin-page [system]
  (fn [req]
    (let [config (:config system)
          system-info (cleanup/get-system-info)
          disk-usage (cleanup/calculate-disk-usage config)
          db-size (cleanup/get-db-size config)]
      (html-response
        (v-admin/render {:system-info system-info
                         :disk-usage disk-usage
                         :db-size db-size
                         :csrf-token (csrf-token req)})))))

(defn admin-cleanup [system]
  (fn [req]
    (let [config (:config system)
          result (cleanup/cleanup-workspaces! config)]
      (log/info "Admin cleanup:" result)
      (let [system-info (cleanup/get-system-info)
            disk-usage (cleanup/calculate-disk-usage config)
            db-size (cleanup/get-db-size config)]
        (html-response
          (v-admin/render {:system-info system-info
                           :disk-usage disk-usage
                           :db-size db-size
                           :cleanup-result result
                           :csrf-token (csrf-token req)}))))))
