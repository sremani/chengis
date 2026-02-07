(ns chengis.plugin.builtin.git-scm
  "Builtin Git SCM provider plugin.
   Handles git clone, checkout, and metadata extraction."
  (:require [chengis.engine.git :as git]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Git SCM Provider
;; ---------------------------------------------------------------------------

(defrecord GitScmProvider []
  proto/ScmProvider
  (checkout-source [_this source-config workspace-dir commit-override]
    (git/checkout-source! source-config workspace-dir commit-override)))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the Git SCM provider plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "git-scm" "0.1.0" "Built-in Git source code management"
      :provides #{:scm-provider}))
  (registry/register-scm-provider! :git (->GitScmProvider)))
