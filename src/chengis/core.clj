(ns chengis.core
  (:require [chengis.cli.core :as cli])
  (:gen-class))

(defn -main
  "Chengis entry point. Use 'serve' to start web UI, otherwise CLI."
  [& args]
  (if (= "serve" (first args))
    (do
      ;; Require web.server dynamically to avoid loading http-kit for CLI usage
      (require 'chengis.web.server)
      ((resolve 'chengis.web.server/start!) (rest args)))
    (cli/dispatch args)))
