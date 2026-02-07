(ns chengis.web.webhook
  "Webhook endpoint for receiving push events from GitHub/GitLab.
   Validates payload, matches to jobs by repo URL, and triggers builds."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.engine.build-runner :as build-runner]
            [chengis.engine.events :as events]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent RejectedExecutionException]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

;; ---------------------------------------------------------------------------
;; Payload parsing
;; ---------------------------------------------------------------------------

(defn- parse-github-push
  "Extract relevant fields from a GitHub push webhook payload."
  [payload]
  (let [ref-name (get payload "ref" "")
        branch (last (str/split ref-name #"/"))]
    {:provider    :github
     :branch      branch
     :commit      (get-in payload ["head_commit" "id"])
     :author      (get-in payload ["head_commit" "author" "name"])
     :message     (get-in payload ["head_commit" "message"])
     :repo-url    (get-in payload ["repository" "clone_url"])
     :repo-name   (get-in payload ["repository" "full_name"])}))

(defn- parse-gitlab-push
  "Extract relevant fields from a GitLab push webhook payload."
  [payload]
  (let [ref-name (get payload "ref" "")
        branch (last (str/split ref-name #"/"))
        commits (get payload "commits" [])
        head-commit (last commits)]
    {:provider    :gitlab
     :branch      branch
     :commit      (get payload "checkout_sha")
     :author      (get-in head-commit ["author" "name"])
     :message     (get head-commit "message")
     :repo-url    (get-in payload ["project" "git_http_url"])
     :repo-name   (get-in payload ["project" "path_with_namespace"])}))

(defn- detect-provider
  "Detect webhook provider from request headers."
  [req]
  (cond
    (get-in req [:headers "x-github-event"])  :github
    (get-in req [:headers "x-gitlab-event"])  :gitlab
    :else nil))

(defn parse-webhook-payload
  "Parse webhook payload based on detected provider.
   Returns nil if provider is unknown."
  [req body]
  (let [provider (detect-provider req)]
    (case provider
      :github (parse-github-push body)
      :gitlab (parse-gitlab-push body)
      nil)))

;; ---------------------------------------------------------------------------
;; HMAC signature verification
;; ---------------------------------------------------------------------------

(defn- hmac-sha256
  "Compute HMAC-SHA256 of body-bytes with the given secret."
  [secret body-bytes]
  (let [mac (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (let [hash (.doFinal mac body-bytes)]
      (str "sha256=" (apply str (map #(format "%02x" %) (seq hash)))))))

(defn verify-signature
  "Verify GitHub webhook HMAC-SHA256 signature.
   Returns true if valid, or if no secret is configured (skip verification)."
  [secret req body-bytes]
  (if-not secret
    true
    (let [signature (get-in req [:headers "x-hub-signature-256"])]
      (if-not signature
        (do (log/warn "Webhook secret configured but no signature in request")
            false)
        (let [expected (hmac-sha256 secret body-bytes)]
          (= signature expected))))))

;; ---------------------------------------------------------------------------
;; Job matching
;; ---------------------------------------------------------------------------

(defn find-matching-jobs
  "Find all jobs whose pipeline :source :url matches the webhook repo URL."
  [ds repo-url]
  (when repo-url
    (let [jobs (job-store/list-jobs ds)]
      (filter (fn [job]
                (let [source (get-in job [:pipeline :source])]
                  (and source
                       (= :git (:type source))
                       (= (:url source) repo-url))))
              jobs))))

;; ---------------------------------------------------------------------------
;; Handler
;; ---------------------------------------------------------------------------

(defn webhook-handler
  "Ring handler for POST /api/webhook.
   Parses the webhook, finds matching jobs, triggers builds."
  [system build-executor]
  (fn [req]
    (let [body-str (slurp (:body req))
          body-bytes (.getBytes ^String body-str "UTF-8")
          ;; Check webhook secret if configured
          webhook-secret (get-in system [:config :webhook :secret])]
      ;; Verify signature
      (if-not (verify-signature webhook-secret req body-bytes)
        (do (log/warn "Webhook signature verification failed")
            {:status 401
             :headers {"Content-Type" "application/json"}
             :body "{\"error\":\"Invalid signature\"}"})
        ;; Parse JSON
        (let [parsed (try
                       (json/read-str body-str)
                       (catch Exception e
                         (log/error "Failed to parse webhook payload:" (.getMessage e))
                         nil))]
          (if-not parsed
            {:status 400
             :headers {"Content-Type" "application/json"}
             :body "{\"error\":\"Invalid JSON payload\"}"}
            (let [webhook-data (parse-webhook-payload req parsed)]
              (if-not webhook-data
                {:status 400
                 :headers {"Content-Type" "application/json"}
                 :body "{\"error\":\"Unsupported webhook provider\"}"}
                (let [ds (:db system)
                      jobs (find-matching-jobs ds (:repo-url webhook-data))]
                  (log/info "Webhook received from" (name (:provider webhook-data))
                            "repo:" (:repo-name webhook-data)
                            "branch:" (:branch webhook-data)
                            "commit:" (:commit webhook-data))
                  (if (empty? jobs)
                    (do (log/info "No matching jobs for" (:repo-url webhook-data))
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body "{\"triggered\":0,\"message\":\"No matching jobs\"}"})
                    (let [triggered (atom 0)]
                      (doseq [job jobs]
                        (let [build-record (build-store/create-build! ds
                                             {:job-id (:id job)
                                              :trigger-type :scm
                                              :parameters {:branch (:branch webhook-data)
                                                           :commit (:commit webhook-data)}})
                              build-id (:id build-record)]
                          (log/info "Webhook triggered build #" (:build-number build-record)
                                    "for" (:name job)
                                    "branch:" (:branch webhook-data))
                          (try
                            (.submit build-executor
                              ^Runnable (fn []
                                (try
                                  (build-runner/execute-build-for-record!
                                    system job build-record
                                    {:event-fn events/publish!
                                     :parameters {:branch (:branch webhook-data)
                                                  :commit (:commit webhook-data)}})
                                  (catch Exception e
                                    (log/error e "Webhook-triggered build failed:" build-id)
                                    (build-store/update-build-status! ds build-id :failure
                                      :completed-at (str (java.time.Instant/now)))))))
                            (swap! triggered inc)
                            (catch RejectedExecutionException _
                              (log/warn "Build queue full, cannot trigger for" (:name job))))))
                      {:status 200
                       :headers {"Content-Type" "application/json"}
                       :body (str "{\"triggered\":" @triggered "}")})))))))))))
