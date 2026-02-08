(ns chengis.engine.git
  "Git operations via CLI. Uses process/execute-command for all git calls.
   Never logs credentials — URLs are sanitized before logging."
  (:require [chengis.engine.process :as process]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Credential handling
;; ---------------------------------------------------------------------------

(defn sanitize-url
  "Remove credentials from a git URL for safe logging.
   https://user:token@github.com/foo/bar.git -> https://***@github.com/foo/bar.git"
  [url]
  (str/replace (str url) #"(https?://)([^@]+)@" "$1***@"))

(defn- build-clone-env
  "Build environment map for git commands with credential support.
   :ssh-key  - path to private key → sets GIT_SSH_COMMAND
   :token    - HTTPS personal access token → injected into URL instead"
  [credentials]
  (let [{:keys [ssh-key]} credentials]
    (if ssh-key
      {"GIT_SSH_COMMAND" (str "ssh -i " ssh-key " -o StrictHostKeyChecking=accept-new")}
      {})))

(defn- token-url
  "Inject HTTPS token into clone URL if present.
   https://github.com/foo/bar.git -> https://x-access-token:TOKEN@github.com/foo/bar.git"
  [url token]
  (if token
    (str/replace url #"(https?://)" (str "$1x-access-token:" token "@"))
    url))

;; ---------------------------------------------------------------------------
;; Core git operations
;; ---------------------------------------------------------------------------

(defn clone!
  "Clone a git repository into workspace-dir.

   source-config keys:
     :url         - git repo URL (required)
     :branch      - branch to checkout (optional, uses repo default)
     :depth       - shallow clone depth (optional, nil = full clone)
     :credentials - {:ssh-key \"path\"} or {:token \"xxx\"}

   Returns {:success? bool :error str}"
  [source-config workspace-dir]
  (let [{:keys [url branch depth credentials]} source-config
        clone-url (token-url url (:token credentials))
        ;; Build the command parts, properly quoting the URL
        cmd (str "git clone"
                 (when branch (str " -b " branch))
                 (when depth (str " --depth " depth))
                 " -- " (pr-str clone-url) " .")]
    (log/info "Cloning" (sanitize-url url)
              (when branch (str "branch:" branch))
              "into" workspace-dir)
    (let [result (process/execute-command
                   {:command cmd
                    :dir workspace-dir
                    :env (build-clone-env credentials)
                    :timeout 600000})]  ;; 10-minute timeout for large repos
      (if (zero? (:exit-code result))
        {:success? true}
        {:success? false
         :error (str "git clone failed (exit " (:exit-code result) "): "
                     (sanitize-url (or (:stderr result) "")))}))))

(defn checkout!
  "Checkout a specific commit SHA in an already-cloned workspace.
   Returns {:success? bool :error str}"
  [commit workspace-dir]
  (when commit
    (log/info "Checking out commit:" commit)
    (let [result (process/execute-command
                   {:command (str "git checkout " commit)
                    :dir workspace-dir
                    :timeout 30000})]
      (if (zero? (:exit-code result))
        {:success? true}
        {:success? false
         :error (str "git checkout failed: " (:stderr result))}))))

(defn get-git-info
  "Extract git metadata from the workspace after clone/checkout.
   Returns {:branch str :commit str :commit-short str :author str :message str
            :remote-url str} or nil if not a git repo."
  [workspace-dir]
  (let [run-git (fn [args]
                  (let [result (process/execute-command
                                 {:command (str "git " args)
                                  :dir workspace-dir
                                  :timeout 10000})]
                    (when (zero? (:exit-code result))
                      (str/trim (:stdout result)))))]
    (when-let [commit (run-git "rev-parse HEAD")]
      {:commit       commit
       :commit-short (or (run-git "rev-parse --short HEAD") (subs commit 0 (min 7 (count commit))))
       :branch       (or (run-git "rev-parse --abbrev-ref HEAD") "detached")
       :author       (or (run-git "log -1 --format=%an") "")
       :author-email (or (run-git "log -1 --format=%ae") "")
       :message      (or (run-git "log -1 --format=%s") "")
       :remote-url   (run-git "config --get remote.origin.url")})))

;; ---------------------------------------------------------------------------
;; High-level entry point (called by executor)
;; ---------------------------------------------------------------------------

(defn checkout-source!
  "Clone repo, optionally checkout specific commit, return git metadata.

   Arguments:
     source-config   - the :source map from the pipeline definition
     workspace-dir   - the build workspace path
     commit-override - optional commit SHA (e.g., from webhook payload)

   Returns {:success? bool :git-info {...} :error str}"
  [source-config workspace-dir commit-override]
  (let [clone-result (clone! source-config workspace-dir)]
    (if-not (:success? clone-result)
      (assoc clone-result :git-info nil)
      (do
        ;; Checkout specific commit if provided
        (when commit-override
          (let [co-result (checkout! commit-override workspace-dir)]
            (when (and co-result (not (:success? co-result)))
              (log/warn "Commit checkout failed, continuing on branch HEAD:"
                        (:error co-result)))))
        (let [git-info (get-git-info workspace-dir)]
          {:success? true
           :git-info git-info})))))
