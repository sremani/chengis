(ns chengis.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-config
  {:database {:path "chengis.db"}
   :workspace {:root "workspaces"}
   :scheduler {:enabled false}
   :server {:port 8080 :host "0.0.0.0"}
   :secrets {:master-key nil}
   :artifacts {:root "artifacts" :retention-builds 10}
   :notifications {:slack {:default-webhook nil}}
   :cleanup {:enabled false :interval-hours 24 :retention-builds 10}
   :plugins {:directory "plugins" :enabled []}
   :docker {:host "unix:///var/run/docker.sock"
            :default-timeout 600000
            :pull-policy :if-not-present}
   :distributed {:enabled false
                 :mode :master
                 :auth-token nil
                 :agent {:port 9090
                         :labels #{}
                         :max-builds 2}
                 :dispatch {:fallback-local true
                            :queue-enabled false
                            :max-retries 3
                            :retry-backoff-ms 1000
                            :circuit-breaker-threshold 5
                            :circuit-breaker-reset-ms 60000
                            :orphan-check-interval-ms 120000
                            :artifact-transfer true}}
   :auth {:enabled false
          :session-secret nil
          :jwt-secret nil
          :jwt-expiry-hours 24
          :seed-admin-password "admin"
          :session-max-age 86400}
   :https {:enabled false
           :port 8443
           :keystore nil
           :keystore-password nil
           :hsts true
           :redirect-http true}
   :audit {:enabled true
           :retention-days 90
           :buffer-size 1024}
   :metrics {:enabled false
             :path "/metrics"
             :auth-required false}
   :rate-limit {:enabled false
                :requests-per-minute 60
                :auth-requests-per-minute 10
                :webhook-requests-per-minute 120}
   :security {:cors {:enabled false
                     :allowed-origins ["*"]
                     :allowed-methods ["GET" "POST" "PUT" "DELETE"]
                     :max-age 3600}
              :csp {:enabled true
                    :directives {:default-src "'self'"
                                 :script-src "'self' 'unsafe-inline' https://cdn.tailwindcss.com https://unpkg.com"
                                 :style-src "'self' 'unsafe-inline'"
                                 :img-src "'self' data:"
                                 :connect-src "'self'"}}}
   :retention {:enabled false
               :interval-hours 24
               :builds-days 90
               :build-logs-days 30
               :audit-days 90
               :webhook-events-days 30
               :queue-completed-hours 168}
   :log {:level :info
         :format :text
         :file nil}})

(defn load-config
  "Load configuration from config.edn on the classpath, merged with defaults.
   Optionally accepts a path to an external config file."
  ([]
   (if-let [resource (io/resource "config.edn")]
     (merge default-config (edn/read-string {:readers {}} (slurp resource)))
     default-config))
  ([path]
   (merge default-config (edn/read-string {:readers {}} (slurp path)))))

(defn resolve-path
  "Resolve a potentially relative path against a base directory."
  [base path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      (.getAbsolutePath f)
      (.getAbsolutePath (io/file base path)))))
