(ns chengis.web.webhook
  "Webhook endpoint for receiving push events from GitHub/GitLab.
   Validates payload, matches to jobs by repo URL, and triggers builds."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.db.webhook-log :as webhook-log]
            [chengis.engine.build-runner :as build-runner]
            [chengis.engine.events :as events]
            [chengis.metrics :as metrics]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent RejectedExecutionException]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.security MessageDigest]))

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
;; Webhook signature / token verification (provider-aware)
;; ---------------------------------------------------------------------------

(defn- hmac-sha256
  "Compute HMAC-SHA256 of body-bytes with the given secret."
  [secret body-bytes]
  (let [mac (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (let [hash (.doFinal mac body-bytes)]
      (str "sha256=" (apply str (map #(format "%02x" %) (seq hash)))))))

(defn- constant-time-equals?
  "Constant-time string comparison to prevent timing attacks.
   Uses MessageDigest/isEqual which is designed for this purpose."
  [^String a ^String b]
  (when (and a b)
    (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))

(defn- verify-github-signature
  "Verify GitHub webhook HMAC-SHA256 signature via x-hub-signature-256 header.
   Returns true if valid, or if no secret is configured (skip verification).
   Uses constant-time comparison to prevent timing attacks."
  [secret req body-bytes]
  (if-not secret
    true
    (let [signature (get-in req [:headers "x-hub-signature-256"])]
      (if-not signature
        (do (log/warn "Webhook secret configured but no GitHub signature in request")
            false)
        (let [expected (hmac-sha256 secret body-bytes)]
          (boolean (constant-time-equals? signature expected)))))))

(defn- verify-gitlab-token
  "Verify GitLab webhook secret token via x-gitlab-token header.
   GitLab uses plain token comparison (no HMAC).
   Returns true if valid, or if no secret is configured (skip verification).
   Uses constant-time comparison to prevent timing attacks."
  [secret req]
  (if-not secret
    true
    (let [token (get-in req [:headers "x-gitlab-token"])]
      (if-not token
        (do (log/warn "Webhook secret configured but no GitLab token in request")
            false)
        (boolean (constant-time-equals? secret token))))))

(defn verify-webhook-signature
  "Verify webhook signature/token based on provider.
   GitHub uses HMAC-SHA256 (x-hub-signature-256), GitLab uses plain token (x-gitlab-token).
   Returns true if valid, or if no secret is configured (skip verification)."
  [secret provider req body-bytes]
  (case provider
    :github (verify-github-signature secret req body-bytes)
    :gitlab (verify-gitlab-token secret req)
    (do (log/warn "Unknown webhook provider for signature verification")
        false)))

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

(defn- log-webhook-event!
  "Log a webhook event to the database (non-blocking, swallows errors)."
  [ds registry event]
  (try
    (webhook-log/log-webhook-event! ds event)
    (metrics/record-webhook-received! registry (:provider event) (:status event))
    (when (:processing-ms event)
      (metrics/record-webhook-processing! registry (/ (double (:processing-ms event)) 1000.0)))
    (catch Exception _)))

(defn webhook-handler
  "Ring handler for POST /api/webhook.
   Detects provider first (GitHub/GitLab), verifies signature/token,
   then parses payload, matches to jobs, and triggers builds.
   Logs every webhook event to the database for audit + debugging."
  [system build-executor]
  (fn [req]
    (let [start-ms (System/currentTimeMillis)
          body-str (slurp (:body req))
          body-bytes (.getBytes ^String body-str "UTF-8")
          payload-size (count body-bytes)
          webhook-secret (get-in system [:config :webhook :secret])
          ds (:db system)
          registry (:metrics system)
          ;; Detect provider FIRST — needed for provider-aware signature check
          provider (detect-provider req)
          event-type (or (get-in req [:headers "x-github-event"])
                         (get-in req [:headers "x-gitlab-event"]))]
      ;; Reject unknown provider early
      (if-not provider
        (do (log-webhook-event! ds registry
              {:provider :unknown :event-type event-type :status :rejected
               :error "Unsupported webhook provider" :payload-size payload-size
               :processing-ms (- (System/currentTimeMillis) start-ms)})
            {:status 400
             :headers {"Content-Type" "application/json"}
             :body "{\"error\":\"Unsupported webhook provider\"}"})
        ;; Verify signature/token with provider context
        (if-not (verify-webhook-signature webhook-secret provider req body-bytes)
          (do (log/warn "Webhook signature verification failed for" (name provider))
              (log-webhook-event! ds registry
                {:provider provider :event-type event-type :signature-valid false
                 :status :rejected :error "Invalid signature" :payload-size payload-size
                 :processing-ms (- (System/currentTimeMillis) start-ms)})
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
              (do (log-webhook-event! ds registry
                    {:provider provider :event-type event-type :status :error
                     :error "Invalid JSON payload" :payload-size payload-size
                     :processing-ms (- (System/currentTimeMillis) start-ms)})
                  {:status 400
                   :headers {"Content-Type" "application/json"}
                   :body "{\"error\":\"Invalid JSON payload\"}"})
              (let [webhook-data (case provider
                                   :github (parse-github-push parsed)
                                   :gitlab (parse-gitlab-push parsed))]
                (let [jobs (find-matching-jobs ds (:repo-url webhook-data))]
                  (log/info "Webhook received from" (name (:provider webhook-data))
                            "repo:" (:repo-name webhook-data)
                            "branch:" (:branch webhook-data)
                            "commit:" (:commit webhook-data))
                  (if (empty? jobs)
                    (let [duration (- (System/currentTimeMillis) start-ms)]
                      (log/info "No matching jobs for" (:repo-url webhook-data))
                      (log-webhook-event! ds registry
                        {:provider provider :event-type event-type
                         :repo-url (:repo-url webhook-data) :repo-name (:repo-name webhook-data)
                         :branch (:branch webhook-data) :commit-sha (:commit webhook-data)
                         :status :processed :matched-jobs 0 :triggered-builds 0
                         :payload-size payload-size :processing-ms duration})
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
                      (let [duration (- (System/currentTimeMillis) start-ms)]
                        (log-webhook-event! ds registry
                          {:provider provider :event-type event-type
                           :repo-url (:repo-url webhook-data) :repo-name (:repo-name webhook-data)
                           :branch (:branch webhook-data) :commit-sha (:commit webhook-data)
                           :status :processed :matched-jobs (count jobs)
                           :triggered-builds @triggered
                           :payload-size payload-size :processing-ms duration})
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (str "{\"triggered\":" @triggered "}")}))))))))))))

