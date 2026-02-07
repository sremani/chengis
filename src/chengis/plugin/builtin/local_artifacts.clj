(ns chengis.plugin.builtin.local-artifacts
  "Builtin local filesystem artifact handler plugin.
   Collects build artifacts to a local directory."
  (:require [chengis.engine.artifacts :as artifacts]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Local Artifact Handler
;; ---------------------------------------------------------------------------

(defrecord LocalArtifactHandler []
  proto/ArtifactHandler
  (collect-artifacts [_this workspace-dir artifact-dir patterns]
    (artifacts/collect-artifacts! workspace-dir artifact-dir patterns)))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the local artifact handler plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "local-artifacts" "0.1.0" "Local filesystem artifact collection"
      :provides #{:artifact-handler}))
  (registry/register-artifact-handler! "local" (->LocalArtifactHandler)))
