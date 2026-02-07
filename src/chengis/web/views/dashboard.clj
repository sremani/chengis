(ns chengis.web.views.dashboard
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]))

(defn render
  "Dashboard page: stats overview + recent builds."
  [{:keys [jobs builds running-count queued-count csrf-token]}]
  (layout/base-layout
    {:title "Dashboard" :csrf-token csrf-token}
    ;; Stats row
    [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 mb-8"}
     (c/stat-card (count jobs) "Jobs")
     (c/stat-card (count builds) "Total Builds")
     (c/stat-card running-count "Running" {:color "text-blue-600"})
     (c/stat-card queued-count "Queued" {:color "text-yellow-600"})]
    ;; Recent builds
    [:div {:class "bg-white rounded-lg shadow-sm border"}
     [:div {:class "px-5 py-4 border-b flex items-center justify-between"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Recent Builds"]
      [:a {:href "/jobs" :class "text-sm text-blue-600 hover:text-blue-800"} "View all jobs"]]
     [:div {:class "p-0"}
      (c/build-table (take 20 builds) {:show-job? true})]]))
