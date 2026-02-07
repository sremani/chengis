(ns chengis.web.handlers
  (:require [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.engine.build-runner :as build-runner]
            [chengis.engine.events :as events]
            [chengis.web.views.dashboard :as v-dashboard]
            [chengis.web.views.jobs :as v-jobs]
            [chengis.web.views.builds :as v-builds]
            [chengis.web.sse :as sse]
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
          queued (count (filter #(= :queued (:status %)) builds))]
      (html-response
        (v-dashboard/render {:jobs jobs
                             :builds builds
                             :running-count running
                             :queued-count queued
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
        (let [builds (build-store/list-builds ds (:id job))]
          (html-response
            (v-jobs/render-detail {:job job :builds builds
                                   :csrf-token (csrf-token req)})))))))

(defn trigger-build [system]
  (fn [req]
    (let [ds (:db system)
          job-name (get-in req [:path-params :name])
          job (job-store/get-job ds job-name)]
      (if-not job
        (not-found (str "Job not found: " job-name))
        (let [build-record (build-store/create-build! ds
                             {:job-id (:id job)
                              :trigger-type :manual})
              build-id (:id build-record)]
          (log/info "Web trigger: build #" (:build-number build-record) "for" job-name "(id:" build-id ")")
          ;; Run build on bounded thread pool
          (try
            (.submit build-runner/build-executor
              ^Runnable (fn []
                (try
                  (build-runner/execute-build-for-record!
                    system job build-record
                    {:event-fn events/publish!})
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
              job (job-store/get-job-by-id ds (:job-id build))]
          (html-response
            (v-builds/render-detail {:build build
                                     :stages stages
                                     :steps steps
                                     :job job
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

(defn build-events-sse [system]
  (fn [req]
    (let [build-id (get-in req [:path-params :id])]
      ((sse/sse-handler system build-id) req))))
