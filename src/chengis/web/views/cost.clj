(ns chengis.web.views.cost
  "Build cost attribution dashboard views."
  (:require [chengis.web.views.layout :as layout]))

(defn- format-cost
  "Format a cost value as a dollar string."
  [cost]
  (if cost
    (format "$%.4f" (double cost))
    "—"))

(defn- format-duration
  "Format duration in seconds to a human-readable string."
  [s]
  (cond
    (nil? s) "—"
    (< s 60) (str (format "%.1f" (double s)) "s")
    (< s 3600) (str (format "%.1f" (/ (double s) 60.0)) "m")
    :else (str (format "%.2f" (/ (double s) 3600.0)) "h")))

(defn- cost-summary-row
  "Render a row in the cost summary table."
  [entry]
  [:tr {:class "hover:bg-gray-50"}
   [:td {:class "px-4 py-3 text-sm font-medium"}
    [:a {:href (str "/jobs/" (:job-id entry))
         :class "text-blue-600 hover:text-blue-800"}
     (:job-id entry)]]
   [:td {:class "px-4 py-3 text-sm text-right"} (or (:build-count entry) 0)]
   [:td {:class "px-4 py-3 text-sm text-right"} (format-duration (:total-duration-s entry))]
   [:td {:class "px-4 py-3 text-sm text-right font-medium text-green-700"}
    (format-cost (:total-cost entry))]])

(defn cost-page
  "Render the build cost attribution dashboard."
  [{:keys [cost-summary total-cost csrf-token]}]
  (layout/base-layout
    {:title "Build Costs" :csrf-token csrf-token}
    [:div {:class "space-y-6"}
     [:div {:class "flex items-center justify-between"}
      [:h1 {:class "text-2xl font-bold text-gray-900"} "Build Cost Attribution"]
      [:div {:class "bg-white rounded-lg border px-4 py-2"}
       [:span {:class "text-sm text-gray-500"} "Total Cost: "]
       [:span {:class "text-lg font-bold text-green-700"} (format-cost total-cost)]]]

     (if (empty? cost-summary)
       [:div {:class "bg-yellow-50 border border-yellow-200 rounded-lg p-6 text-center"}
        [:p {:class "text-yellow-800 font-medium"} "No cost data yet."]
        [:p {:class "text-yellow-600 text-sm mt-1"}
         "Cost data is recorded when builds complete with the cost-attribution feature enabled."]]
       [:div {:class "bg-white rounded-lg shadow-sm border"}
        [:div {:class "px-5 py-4 border-b"}
         [:h2 {:class "text-lg font-semibold text-gray-900"} "Cost by Job"]]
        [:table {:class "min-w-full divide-y divide-gray-200"}
         [:thead {:class "bg-gray-50"}
          [:tr
           [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Job"]
           [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Builds"]
           [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Total Time"]
           [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Total Cost"]]]
         [:tbody {:class "divide-y divide-gray-200"}
          (for [entry cost-summary]
            (cost-summary-row entry))]]])]))
