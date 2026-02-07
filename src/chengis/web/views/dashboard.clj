(ns chengis.web.views.dashboard
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]))

(defn- alerts-panel
  "Alerts panel with htmx polling for live updates."
  [initial-alerts]
  [:div {:class "mb-6"}
   [:div {:id "alerts-panel"
          :hx-get "/api/alerts/fragment"
          :hx-trigger "every 15s"
          :hx-swap "innerHTML"}
    ;; Initial render of alerts (if any)
    (when (seq initial-alerts)
      (for [{:keys [level message]} initial-alerts]
        (let [color (if (= :critical level) "red" "yellow")]
          [:div {:class (str "flex items-center gap-2 px-4 py-2 rounded-lg bg-"
                             color "-50 border border-" color "-200 mb-2")}
           [:span {:class (str "inline-block w-2 h-2 rounded-full bg-" color "-500")}]
           [:span {:class (str "text-sm text-" color "-800")} message]])))]])

(defn render
  "Dashboard page: stats overview + build trends + recent builds + alerts."
  [{:keys [jobs builds running-count queued-count stats recent-history alerts csrf-token]}]
  (layout/base-layout
    {:title "Dashboard" :csrf-token csrf-token}

    ;; Alerts panel (htmx auto-refreshing)
    (alerts-panel alerts)

    ;; Stats row
    [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 mb-8"}
     (c/stat-card (count jobs) "Jobs")
     (c/stat-card (count builds) "Total Builds")
     (c/stat-card running-count "Running" {:color "text-blue-600"})
     (c/stat-card queued-count "Queued" {:color "text-yellow-600"})]

    ;; Build stats & history chart
    (when (and stats (pos? (:total stats)))
      (list
        (c/build-stats-row stats)
        (c/build-history-chart recent-history)))

    ;; Recent builds
    [:div {:class "bg-white rounded-lg shadow-sm border"}
     [:div {:class "px-5 py-4 border-b flex items-center justify-between"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Recent Builds"]
      [:a {:href "/jobs" :class "text-sm text-blue-600 hover:text-blue-800"} "View all jobs"]]
     [:div {:class "p-0"}
      (c/build-table (take 20 builds) {:show-job? true})]]))
