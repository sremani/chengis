(ns chengis.engine.webhook-replay
  "Webhook replay — re-deliver failed or historical webhooks.
   Retrieves stored raw payload from webhook_events and re-processes
   it through the webhook handler pipeline."
  (:require [chengis.db.webhook-log :as webhook-log]
            [chengis.feature-flags :as feature-flags]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]))

(defn replay-webhook!
  "Replay a webhook event by re-processing its stored payload.
   Returns {:status :replayed/:error :details str :event-id str}.

   Arguments:
     system         - system map
     event-id       - ID of the webhook event to replay
     webhook-handler-fn - the webhook handler fn to invoke"
  [system event-id webhook-handler-fn]
  (let [ds (:db system)
        config (:config system)]
    (when-not (feature-flags/enabled? config :webhook-replay)
      (throw (ex-info "Webhook replay feature is not enabled"
                      {:type :feature-disabled :flag :webhook-replay})))
    (let [event (webhook-log/get-webhook-event ds event-id)]
      (cond
        (nil? event)
        {:status :error :details "Webhook event not found" :event-id event-id}

        (nil? (:payload-body event))
        {:status :error :details "No stored payload for replay" :event-id event-id}

        :else
        (try
          (let [provider (keyword (:provider event))
                payload-body (:payload-body event)
                ;; Construct a mock Ring request with the provider headers
                mock-req {:headers (case provider
                                     :github {"x-github-event" (or (:event-type event) "push")
                                              "content-type" "application/json"}
                                     :gitlab {"x-gitlab-event" (or (:event-type event) "Push Hook")
                                              "content-type" "application/json"}
                                     {"content-type" "application/json"})
                          :body (java.io.ByteArrayInputStream.
                                  (.getBytes ^String payload-body "UTF-8"))}
                ;; Call the handler (it will skip signature verification for replays)
                response (webhook-handler-fn mock-req)]
            ;; Mark as replayed
            (webhook-log/mark-replayed! ds event-id)
            (log/info "Replayed webhook event" event-id
                      "provider:" (:provider event)
                      "repo:" (:repo-name event))
            {:status :replayed
             :details (str "Replayed successfully, HTTP " (:status response))
             :event-id event-id
             :http-status (:status response)})
          (catch Exception e
            (log/warn "Webhook replay failed for event" event-id ":" (.getMessage e))
            {:status :error
             :details (.getMessage e)
             :event-id event-id}))))))

(defn list-replayable
  "List webhook events eligible for replay (have stored payload).
   Returns a seq of event summary maps."
  [system & {:keys [provider org-id limit offset]}]
  (let [ds (:db system)]
    (webhook-log/list-replayable-events ds
      :provider provider :org-id org-id
      :limit (or limit 50) :offset (or offset 0))))
