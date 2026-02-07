(ns chengis.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-config
  {:database {:path "chengis.db"}
   :workspace {:root "workspaces"}
   :log {:level :info}
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
                 :dispatch {:fallback-local true}}})

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
