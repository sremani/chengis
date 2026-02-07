(ns chengis.model.spec
  (:require [clojure.spec.alpha :as s]))

;; TODO: Key mismatches — these specs use ::step-type, ::condition-type, ::condition-value
;; but actual data uses :type and :value. Not currently validated at runtime.
;; Fix when integrating spec validation into DSL/Chengisfile pipeline.

;; --- Step ---

(s/def ::step-name string?)
(s/def ::command string?)
(s/def ::step-type #{:shell})
(s/def ::env (s/map-of string? string?))
(s/def ::dir (s/nilable string?))
(s/def ::timeout (s/nilable pos-int?))

(s/def ::step
  (s/keys :req-un [::step-name ::step-type ::command]
          :opt-un [::env ::dir ::timeout]))

;; --- Stage ---

(s/def ::stage-name string?)
(s/def ::parallel? boolean?)
(s/def ::steps (s/coll-of ::step :min-count 1))
(s/def ::condition-type #{:branch :param :always})
(s/def ::condition-value string?)
(s/def ::condition
  (s/keys :req-un [::condition-type ::condition-value]))

(s/def ::stage
  (s/keys :req-un [::stage-name ::steps]
          :opt-un [::parallel? ::condition]))

;; --- Git Source ---

(s/def ::source-type #{:git})
(s/def ::url string?)
(s/def ::depth (s/nilable pos-int?))
(s/def ::ssh-key (s/nilable string?))
(s/def ::token (s/nilable string?))
(s/def ::credentials
  (s/keys :opt-un [::ssh-key ::token]))

(s/def ::source
  (s/keys :req-un [::source-type ::url]
          :opt-un [::branch ::depth ::credentials]))

;; --- Git Info (returned from checkout) ---

(s/def ::commit string?)
(s/def ::commit-short string?)
(s/def ::branch string?)
(s/def ::author string?)
(s/def ::author-email string?)
(s/def ::message string?)

(s/def ::git-info
  (s/keys :req-un [::commit ::branch]
          :opt-un [::commit-short ::author ::author-email ::message]))

;; --- Pipeline ---

(s/def ::pipeline-name string?)
(s/def ::description (s/nilable string?))
(s/def ::stages (s/coll-of ::stage :min-count 1))
(s/def ::parameter-default (s/keys :req-un [::default]))
(s/def ::default string?)
(s/def ::parameters (s/map-of keyword? ::parameter-default))

(s/def ::pipeline
  (s/keys :req-un [::pipeline-name ::stages]
          :opt-un [::description ::parameters ::source]))

;; --- Build Status ---

(s/def ::build-status #{:queued :running :success :failure :aborted})
(s/def ::step-status #{:pending :running :success :failure :skipped})
(s/def ::stage-status #{:pending :running :success :failure :skipped})
(s/def ::trigger-type #{:manual :cron :scm})

;; --- Build ---

(s/def ::build-id string?)
(s/def ::job-id string?)
(s/def ::build-number pos-int?)
(s/def ::workspace (s/nilable string?))
(s/def ::started-at (s/nilable inst?))
(s/def ::completed-at (s/nilable inst?))

(s/def ::build
  (s/keys :req-un [::build-id ::job-id ::build-number ::build-status]
          :opt-un [::workspace ::trigger-type ::started-at ::completed-at ::parameters]))

;; --- Step Result ---

(s/def ::exit-code int?)
(s/def ::stdout string?)
(s/def ::stderr string?)
(s/def ::duration-ms nat-int?)

(s/def ::step-result
  (s/keys :req-un [::step-name ::step-status ::exit-code]
          :opt-un [::stdout ::stderr ::duration-ms]))

;; --- Stage Result ---

(s/def ::step-results (s/coll-of ::step-result))

(s/def ::stage-result
  (s/keys :req-un [::stage-name ::stage-status]
          :opt-un [::step-results ::started-at ::completed-at]))

;; --- Build Result ---

(s/def ::stage-results (s/coll-of ::stage-result))

(s/def ::build-result
  (s/keys :req-un [::build-id ::build-status]
          :opt-un [::stage-results ::started-at ::completed-at ::duration-ms]))

;; --- Valid state transitions ---

(def valid-transitions
  {:queued   #{:running :aborted}
   :running  #{:success :failure :aborted}
   :success  #{}
   :failure  #{}
   :aborted  #{}})

(defn valid-transition?
  "Check if transitioning from `from` to `to` is a valid build state change."
  [from to]
  (contains? (get valid-transitions from) to))
