(ns chengis.plugin.builtin.yaml-format
  "Builtin YAML pipeline format plugin.
   Registers .yml/.yaml as a recognized pipeline format."
  (:require [chengis.dsl.yaml :as yaml]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]))

;; ---------------------------------------------------------------------------
;; YAML Pipeline Format
;; ---------------------------------------------------------------------------

(defrecord YamlPipelineFormat []
  proto/PipelineFormat
  (parse-pipeline [_this file-path]
    (yaml/parse-yaml-workflow file-path))

  (detect-file [_this workspace-dir]
    (yaml/detect-yaml-file workspace-dir)))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the YAML pipeline format plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "yaml-format" "0.1.0" "GitHub Actions-style YAML pipeline format"
      :provides #{:pipeline-format}))
  (let [fmt (->YamlPipelineFormat)]
    (registry/register-pipeline-format! "yaml" fmt)
    (registry/register-pipeline-format! "yml" fmt)))
