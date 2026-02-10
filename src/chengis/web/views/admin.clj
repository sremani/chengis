(ns chengis.web.views.admin
  "Admin dashboard page: system stats, disk usage, cleanup controls."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]))

(defn- stat-card-small
  "Compact stat card for the admin grid."
  [label value & [{:keys [sublabel color] :or {color "text-gray-900"}}]]
  [:div {:class "bg-white rounded-lg shadow-sm border p-4"}
   [:div {:class "text-xs text-gray-500 mb-1"} label]
   [:div {:class (str "text-xl font-bold " color)} value]
   (when sublabel
     [:div {:class "text-xs text-gray-400 mt-1"} sublabel])])

(defn- memory-bar
  "Visual memory usage bar."
  [pct]
  (let [color (cond
                (nil? pct) "bg-gray-300"
                (< pct 60) "bg-green-500"
                (< pct 85) "bg-yellow-500"
                :else "bg-red-500")]
    [:div {:class "w-full bg-gray-200 rounded-full h-3 mt-2"}
     [:div {:class (str "h-3 rounded-full " color)
            :style (str "width: " (or pct 0) "%")}]]))

(defn render
  "Admin dashboard page."
  [{:keys [system-info disk-usage db-size cleanup-result csrf-token user auth-enabled]}]
  (let [jvm (:jvm system-info)
        uptime (:uptime system-info)
        os (:os system-info)
        pool (:build-pool system-info)
        active (:active-builds system-info)
        ws-usage (:workspaces disk-usage)
        art-usage (:artifacts disk-usage)]
    (layout/base-layout
      {:title "Admin" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
      (c/page-header "Admin Dashboard")

      ;; Cleanup result flash message
      (when cleanup-result
        [:div {:class (str "rounded-lg border p-4 mb-6 "
                           (if (pos? (:cleaned cleanup-result))
                             "bg-green-50 border-green-200 text-green-700"
                             "bg-blue-50 border-blue-200 text-blue-700"))}
         (str "Cleanup complete: " (:cleaned cleanup-result) " workspaces removed, "
              (:freed cleanup-result) " freed.")])

      ;; System stats grid
      [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 mb-6"}
       (stat-card-small "Uptime" (:formatted uptime))
       (stat-card-small "Heap Memory"
                        (str (:heap-used jvm) " / " (:heap-max jvm))
                        {:sublabel (str (:heap-pct jvm) "% used")})
       (stat-card-small "OS" (str (:name os) " " (:arch os))
                        {:sublabel (str (:processors os) " CPUs")})
       (stat-card-small "Load Average"
                        (if (neg? (:load-average os))
                          "N/A"
                          (format "%.2f" (:load-average os))))]

      ;; Memory bar
      [:div {:class "bg-white rounded-lg shadow-sm border p-5 mb-6"}
       [:h2 {:class "text-sm font-semibold text-gray-900 mb-2"} "Heap Memory"]
       [:div {:class "flex items-center gap-4"}
        [:div {:class "flex-1"}
         (memory-bar (:heap-pct jvm))]
        [:span {:class "text-sm text-gray-500"}
         (str (:heap-pct jvm) "%")]]]

      ;; Build pool stats
      [:div {:class "bg-white rounded-lg shadow-sm border p-5 mb-6"}
       [:h2 {:class "text-lg font-semibold text-gray-900 mb-4"} "Build Executor"]
       [:div {:class "grid grid-cols-2 md:grid-cols-5 gap-4 text-sm"}
        [:div
         [:span {:class "text-gray-500 block"} "Active Builds"]
         [:span {:class "text-xl font-bold"
                 :style (when (pos? (:active pool)) "color: #2563eb")}
          (:active pool)]]
        [:div
         [:span {:class "text-gray-500 block"} "Pool Size"]
         [:span {:class "font-medium"} (str (:pool-size pool) " / " (:max-pool-size pool))]]
        [:div
         [:span {:class "text-gray-500 block"} "Queued"]
         [:span {:class "font-medium"} (:queued pool)]]
        [:div
         [:span {:class "text-gray-500 block"} "Completed (Total)"]
         [:span {:class "font-medium"} (:completed pool)]]
        [:div
         [:span {:class "text-gray-500 block"} "Active Build IDs"]
         (if (seq (:ids active))
           [:div {:class "space-y-1"}
            (for [id (:ids active)]
              [:a {:href (str "/builds/" id)
                   :class "block text-xs text-blue-600 hover:underline font-mono truncate max-w-xs"}
               id])]
           [:span {:class "text-gray-400 text-xs"} "None"])]]]

      ;; Quick links
      [:div {:class "bg-white rounded-lg shadow-sm border p-5 mb-6"}
       [:h2 {:class "text-lg font-semibold text-gray-900 mb-3"} "Administration"]
       [:div {:class "flex flex-wrap gap-3"}
        [:a {:href "/admin/audit"
             :class "px-4 py-2 bg-gray-100 rounded text-sm font-medium text-gray-700 hover:bg-gray-200 transition"}
         "Audit Log"]
        [:a {:href "/admin/webhooks"
             :class "px-4 py-2 bg-gray-100 rounded text-sm font-medium text-gray-700 hover:bg-gray-200 transition"}
         "Webhook Events"]
        [:a {:href "/admin/users"
             :class "px-4 py-2 bg-gray-100 rounded text-sm font-medium text-gray-700 hover:bg-gray-200 transition"}
         "User Management"]
        [:a {:href "/admin/compliance"
             :class "px-4 py-2 bg-gray-100 rounded text-sm font-medium text-gray-700 hover:bg-gray-200 transition"}
         "Compliance Reports"]
        [:a {:href "/admin/policies"
             :class "px-4 py-2 bg-gray-100 rounded text-sm font-medium text-gray-700 hover:bg-gray-200 transition"}
         "Policies"]
        [:a {:href "/admin/plugins/policies"
             :class "px-4 py-2 bg-gray-100 rounded text-sm font-medium text-gray-700 hover:bg-gray-200 transition"}
         "Plugin Policies"]
        [:a {:href "/admin/docker/policies"
             :class "px-4 py-2 bg-gray-100 rounded text-sm font-medium text-gray-700 hover:bg-gray-200 transition"}
         "Docker Policies"]
        [:a {:href "/admin/permissions"
             :class "px-4 py-2 bg-gray-100 rounded text-sm font-medium text-gray-700 hover:bg-gray-200 transition"}
         "Permissions"]
        [:a {:href "/admin/shared-resources"
             :class "px-4 py-2 bg-gray-100 rounded text-sm font-medium text-gray-700 hover:bg-gray-200 transition"}
         "Shared Resources"]
        [:a {:href "/admin/rotation"
             :class "px-4 py-2 bg-gray-100 rounded text-sm font-medium text-gray-700 hover:bg-gray-200 transition"}
         "Secret Rotation"]
        [:form {:method "POST" :action "/admin/retention" :class "inline"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
         [:button {:type "submit"
                   :class "px-4 py-2 bg-orange-100 rounded text-sm font-medium text-orange-700 hover:bg-orange-200 transition"
                   :onclick "return confirm('Run data retention cleanup now?')"}
          "Run Retention Now"]]
        [:form {:method "POST" :action "/admin/backup" :class "inline"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
         [:button {:type "submit"
                   :class "px-4 py-2 bg-blue-100 rounded text-sm font-medium text-blue-700 hover:bg-blue-200 transition"}
          "Download Backup"]]]]

      ;; Disk usage
      [:div {:class "bg-white rounded-lg shadow-sm border p-5 mb-6"}
       [:div {:class "flex items-center justify-between mb-4"}
        [:h2 {:class "text-lg font-semibold text-gray-900"} "Disk Usage"]
        [:form {:method "POST" :action "/admin/cleanup"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
         [:button {:type "submit"
                   :class "bg-red-600 text-white px-3 py-1.5 rounded text-sm font-medium
                           hover:bg-red-700 transition-colors"
                   :onclick "return confirm('Clean up old workspaces?')"}
          "Cleanup Workspaces"]]]
       [:div {:class "grid grid-cols-2 md:grid-cols-3 gap-6 text-sm"}
        [:div
         [:span {:class "text-gray-500 block"} "Workspaces"]
         [:span {:class "text-lg font-bold"} (:total ws-usage)]]
        [:div
         [:span {:class "text-gray-500 block"} "Artifacts"]
         [:span {:class "text-lg font-bold"} (:total art-usage)]]
        (when db-size
          [:div
           [:span {:class "text-gray-500 block"} "Database"]
           [:span {:class "text-lg font-bold"} (:formatted db-size)]])]
       ;; Per-job workspace breakdown
       (when (seq (:per-job ws-usage))
         [:div {:class "mt-4 border-t pt-4"}
          [:h3 {:class "text-sm font-medium text-gray-700 mb-2"} "Workspaces by Job"]
          [:table {:class "w-full text-sm"}
           [:thead
            [:tr {:class "text-left text-gray-500 border-b"}
             [:th {:class "py-1 font-medium"} "Job"]
             [:th {:class "py-1 font-medium"} "Size"]
             [:th {:class "py-1 font-medium"} "Builds"]]]
           [:tbody
            (for [[job-name info] (sort-by (comp :bytes val) > (:per-job ws-usage))]
              [:tr {:class "border-b border-gray-100"}
               [:td {:class "py-1 font-mono text-sm"} job-name]
               [:td {:class "py-1"} (:formatted info)]
               [:td {:class "py-1"} (:build-count info)]])]]])])))
