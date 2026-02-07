(ns chengis.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-config
  {:database {:path "chengis.db"}
   :workspace {:root "workspaces"}
   :log {:level :info}
   :scheduler {:enabled false}
   :server {:port 8080 :host "0.0.0.0"}})

(defn load-config
  "Load configuration from config.edn on the classpath, merged with defaults.
   Optionally accepts a path to an external config file."
  ([]
   (if-let [resource (io/resource "config.edn")]
     (merge default-config (edn/read-string (slurp resource)))
     default-config))
  ([path]
   (merge default-config (edn/read-string (slurp path)))))

(defn resolve-path
  "Resolve a potentially relative path against a base directory."
  [base path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      (.getAbsolutePath f)
      (.getAbsolutePath (io/file base path)))))
