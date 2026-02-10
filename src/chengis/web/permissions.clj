(ns chengis.web.permissions
  "Fine-grained permission middleware and helpers.
   Adds resource-level access control on top of existing 3-tier RBAC.
   Admins always bypass. Open-by-default when no permissions configured.
   Feature-flag gated via :fine-grained-rbac."
  (:require [chengis.db.permission-store :as permission-store]
            [chengis.feature-flags :as feature-flags]
            [chengis.web.auth :as auth]
            [taoensso.timbre :as log]))

(defn has-permission?
  "Check if the current user has permission for a resource action.
   Returns true if any of:
   (a) :fine-grained-rbac feature flag is off — all access permitted
   (b) user is admin — admins always bypass fine-grained checks
   (c) no permissions configured for the resource — open-by-default
   (d) user has explicit permission (direct or group-based)"
  [system req resource-type resource-id action]
  (let [config (:config system)
        ds (:db system)
        user (auth/current-user req)
        org-id (or (auth/current-org-id req) "default-org")]
    (cond
      ;; (a) Feature flag off — permissive
      (not (feature-flags/enabled? config :fine-grained-rbac))
      true

      ;; (b) Admin bypass
      (and user (= (keyword (:role user)) :admin))
      true

      ;; No authenticated user — deny
      (nil? user)
      false

      ;; (c) Open-by-default: no permissions configured for this resource
      :else
      (let [resource-perms (permission-store/list-resource-permissions
                             ds resource-type resource-id :org-id org-id)]
        (if (empty? resource-perms)
          ;; No permissions configured — open by default
          true
          ;; (d) Check explicit permission
          (permission-store/check-permission
            ds org-id (:id user) resource-type resource-id action))))))

(defn wrap-require-permission
  "Ring middleware that checks fine-grained permissions.
   resource-id-fn is a function that extracts the resource-id from the request
   (e.g., #(get-in % [:path-params :name])).
   If the user lacks permission, returns 403.
   Must be applied AFTER wrap-auth."
  [resource-type resource-id-fn action handler system]
  (fn [req]
    (let [resource-id (resource-id-fn req)]
      (if (has-permission? system req resource-type resource-id action)
        (handler req)
        (do
          (log/warn "Permission denied"
                    {:user (get-in req [:auth/user :username])
                     :resource-type resource-type
                     :resource-id resource-id
                     :action action})
          (let [api? (let [uri (or (:uri req) "")
                           accept (get-in req [:headers "accept"] "")]
                       (or (clojure.string/starts-with? uri "/api/")
                           (clojure.string/includes? accept "application/json")))]
            (if api?
              {:status 403
               :headers {"Content-Type" "application/json"}
               :body (str "{\"error\":\"Permission denied: " action " on " resource-type "/" resource-id "\"}")}
              {:status 403
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body "<h1>403 Forbidden</h1><p>You don't have permission to access this resource.</p>"})))))))
