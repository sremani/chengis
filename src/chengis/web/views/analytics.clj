(ns chengis.web.views.analytics
  "Build analytics dashboard views.
   Displays build trends, success rates, slowest stages, and flaky stages."
  (:require [chengis.web.views.layout :as layout]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- format-rate
  "Format a success rate as a percentage string."
  [rate]
  (if rate
    (str (format "%.1f" (* 100.0 (double rate))) "%")
    "—"))

(defn- format-duration
  "Format duration in seconds to a human-readable string."
  [s]
  (cond
    (nil? s) "—"
    (< s 1) (str (format "%.0f" (* s 1000.0)) "ms")
    (< s 60) (str (format "%.1f" (double s)) "s")
    :else (str (format "%.1f" (/ (double s) 60.0)) "m")))

(defn- format-period
  "Format a period start string for display (just the date part)."
  [period-start]
  (when period-start
    (subs (str period-start) 0 (min 10 (count (str period-start))))))

(defn- rate-color
  "CSS color class based on success rate."
  [rate]
  (cond
    (nil? rate)   "text-gray-500"
    (>= rate 0.9) "text-green-600"
    (>= rate 0.7) "text-yellow-600"
    :else          "text-red-600"))

(defn- flakiness-color
  "CSS color class based on flakiness score."
  [score]
  (cond
    (nil? score) "text-gray-500"
    (< score 0.2) "text-green-600"
    (< score 0.5) "text-yellow-600"
    :else          "text-red-600"))

;; ---------------------------------------------------------------------------
;; Trend chart (CSS bar chart)
;; ---------------------------------------------------------------------------

(defn- trend-bar
  "Render a single bar in the trend chart."
  [item max-builds]
  (let [total (or (:total-builds item) 0)
        success (or (:success-count item) 0)
        failure (or (:failure-count item) 0)
        height-pct (if (pos? max-builds)
                     (max 5 (* 100.0 (/ (double total) (double max-builds))))
                     5)
        success-pct (if (pos? total) (* 100.0 (/ (double success) (double total))) 0)
        failure-pct (if (pos? total) (* 100.0 (/ (double failure) (double total))) 0)]
    [:div {:class "flex flex-col items-center gap-1 flex-1" :title (str (format-period (:period-start item)) ": " total " builds")}
     [:div {:class "w-full relative bg-gray-100 rounded-t" :style (str "height:" height-pct "px; min-height:4px; max-height:80px;")}
      [:div {:class "absolute bottom-0 w-full bg-green-500 rounded-t"
             :style (str "height:" success-pct "%")}]
      (when (pos? failure-pct)
        [:div {:class "absolute top-0 w-full bg-red-500 rounded-t"
               :style (str "height:" failure-pct "%")}])]
     [:div {:class "text-xs text-gray-400 truncate w-full text-center"}
      (format-period (:period-start item))]]))

(defn- trend-chart
  "Render a CSS bar chart of build trends."
  [trends]
  (if (empty? trends)
    [:p {:class "text-gray-500 text-sm"} "No trend data available yet."]
    (let [reversed (reverse trends)
          max-builds (apply max 1 (map #(or (:total-builds %) 0) reversed))]
      [:div {:class "flex items-end gap-1 overflow-x-auto pb-2"}
       (for [item reversed]
         (trend-bar item max-builds))])))

;; ---------------------------------------------------------------------------
;; Tables
;; ---------------------------------------------------------------------------

(defn- trend-row
  "Render a single row in the build trends table."
  [item]
  [:tr {:class "hover:bg-gray-50"}
   [:td {:class "px-4 py-2 text-sm"} (format-period (:period-start item))]
   [:td {:class "px-4 py-2 text-sm text-right"} (or (:total-builds item) 0)]
   [:td {:class "px-4 py-2 text-sm text-right text-green-600"} (or (:success-count item) 0)]
   [:td {:class "px-4 py-2 text-sm text-right text-red-600"} (or (:failure-count item) 0)]
   [:td {:class (str "px-4 py-2 text-sm text-right font-medium " (rate-color (:success-rate item)))}
    (format-rate (:success-rate item))]
   [:td {:class "px-4 py-2 text-sm text-right"} (format-duration (:avg-duration-s item))]
   [:td {:class "px-4 py-2 text-sm text-right"} (format-duration (:p90-duration-s item))]])

(defn- slowest-stage-row
  "Render a row in the slowest stages table."
  [stage]
  [:tr {:class "hover:bg-gray-50"}
   [:td {:class "px-4 py-2 text-sm font-medium"} (:stage-name stage)]
   [:td {:class "px-4 py-2 text-sm text-right"} (format-duration (:p90-duration-s stage))]
   [:td {:class "px-4 py-2 text-sm text-right"} (format-duration (:avg-duration-s stage))]
   [:td {:class "px-4 py-2 text-sm text-right"} (format-duration (:max-duration-s stage))]
   [:td {:class "px-4 py-2 text-sm text-right"} (or (:total-runs stage) 0)]
   [:td {:class (str "px-4 py-2 text-sm text-right font-medium "
                      (rate-color (when (:total-runs stage)
                                    (if (pos? (:total-runs stage))
                                      (/ (double (or (:success-count stage) 0))
                                         (double (:total-runs stage)))
                                      nil))))}
    (format-rate (when (and (:total-runs stage) (pos? (:total-runs stage)))
                   (/ (double (or (:success-count stage) 0))
                      (double (:total-runs stage)))))]])

(defn- flaky-stage-row
  "Render a row in the flaky stages table."
  [stage]
  [:tr {:class "hover:bg-gray-50"}
   [:td {:class "px-4 py-2 text-sm font-medium"} (:stage-name stage)]
   [:td {:class (str "px-4 py-2 text-sm text-right font-bold " (flakiness-color (:flakiness-score stage)))}
    (when (:flakiness-score stage)
      (format "%.2f" (double (:flakiness-score stage))))]
   [:td {:class "px-4 py-2 text-sm text-right"} (or (:total-runs stage) 0)]
   [:td {:class "px-4 py-2 text-sm text-right text-green-600"} (or (:success-count stage) 0)]
   [:td {:class "px-4 py-2 text-sm text-right text-red-600"} (or (:failure-count stage) 0)]
   [:td {:class "px-4 py-2 text-sm text-right"} (format-period (:period-start stage))]])

;; ---------------------------------------------------------------------------
;; Main analytics page
;; ---------------------------------------------------------------------------

(defn analytics-page
  "Render the build analytics dashboard."
  [{:keys [trends slowest-stages flaky-stages period-type csrf-token]}]
  (let [period (or period-type "daily")]
    (layout/base-layout
      {:title "Build Analytics" :csrf-token csrf-token}
      [:div {:class "space-y-6"}
       ;; Header with period selector
       [:div {:class "flex items-center justify-between"}
        [:h1 {:class "text-2xl font-bold text-gray-900"} "Build Analytics"]
        [:div {:class "flex gap-2"}
         [:a {:href "/analytics?period=daily"
              :class (str "px-3 py-1.5 rounded-lg text-sm font-medium "
                          (if (= period "daily") "bg-blue-600 text-white" "bg-gray-100 text-gray-700 hover:bg-gray-200"))}
          "Daily"]
         [:a {:href "/analytics?period=weekly"
              :class (str "px-3 py-1.5 rounded-lg text-sm font-medium "
                          (if (= period "weekly") "bg-blue-600 text-white" "bg-gray-100 text-gray-700 hover:bg-gray-200"))}
          "Weekly"]]]

       ;; Summary stats (latest period)
       (when-let [latest (first trends)]
         [:div {:class "grid grid-cols-4 gap-4"}
          [:div {:class "bg-white rounded-lg border p-4"}
           [:div {:class "text-sm text-gray-500"} "Total Builds"]
           [:div {:class "text-2xl font-bold"} (or (:total-builds latest) 0)]]
          [:div {:class "bg-white rounded-lg border p-4"}
           [:div {:class "text-sm text-gray-500"} "Success Rate"]
           [:div {:class (str "text-2xl font-bold " (rate-color (:success-rate latest)))}
            (format-rate (:success-rate latest))]]
          [:div {:class "bg-white rounded-lg border p-4"}
           [:div {:class "text-sm text-gray-500"} "Avg Duration"]
           [:div {:class "text-2xl font-bold"} (format-duration (:avg-duration-s latest))]]
          [:div {:class "bg-white rounded-lg border p-4"}
           [:div {:class "text-sm text-gray-500"} "P90 Duration"]
           [:div {:class "text-2xl font-bold"} (format-duration (:p90-duration-s latest))]]])

       ;; Build trend chart
       [:div {:class "bg-white rounded-lg shadow-sm border"}
        [:div {:class "px-5 py-4 border-b"}
         [:h2 {:class "text-lg font-semibold text-gray-900"} "Build Trend"]]
        [:div {:class "p-4"}
         (trend-chart trends)]]

       ;; Build trends table
       (when (seq trends)
         [:div {:class "bg-white rounded-lg shadow-sm border"}
          [:div {:class "px-5 py-4 border-b"}
           [:h2 {:class "text-lg font-semibold text-gray-900"} "Build History"]]
          [:table {:class "min-w-full divide-y divide-gray-200"}
           [:thead {:class "bg-gray-50"}
            [:tr
             [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Period"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Total"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Success"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Failure"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Rate"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Avg"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "P90"]]]
           [:tbody {:class "divide-y divide-gray-200"}
            (for [item trends]
              (trend-row item))]]])

       ;; Slowest stages
       [:div {:class "bg-white rounded-lg shadow-sm border"}
        [:div {:class "px-5 py-4 border-b"}
         [:h2 {:class "text-lg font-semibold text-gray-900"} "Slowest Stages (by P90)"]]
        (if (empty? slowest-stages)
          [:div {:class "px-5 py-8 text-center text-gray-500"} "No stage data available."]
          [:table {:class "min-w-full divide-y divide-gray-200"}
           [:thead {:class "bg-gray-50"}
            [:tr
             [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Stage"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "P90"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Avg"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Max"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Runs"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Rate"]]]
           [:tbody {:class "divide-y divide-gray-200"}
            (for [stage slowest-stages]
              (slowest-stage-row stage))]])]

       ;; Flaky stages
       [:div {:class "bg-white rounded-lg shadow-sm border"}
        [:div {:class "px-5 py-4 border-b"}
         [:h2 {:class "text-lg font-semibold text-gray-900"} "Flaky Stages"]]
        (if (empty? flaky-stages)
          [:div {:class "px-5 py-8 text-center text-gray-500"} "No flaky stages detected."]
          [:table {:class "min-w-full divide-y divide-gray-200"}
           [:thead {:class "bg-gray-50"}
            [:tr
             [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Stage"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Flakiness"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Runs"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Pass"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Fail"]
             [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Period"]]]
           [:tbody {:class "divide-y divide-gray-200"}
            (for [stage flaky-stages]
              (flaky-stage-row stage))]])]

       ;; Empty state when no data
       (when (and (empty? trends) (empty? slowest-stages) (empty? flaky-stages))
         [:div {:class "bg-yellow-50 border border-yellow-200 rounded-lg p-6 text-center"}
          [:p {:class "text-yellow-800 font-medium"} "No analytics data yet."]
          [:p {:class "text-yellow-600 text-sm mt-1"}
           "Analytics are computed periodically. Data will appear after the first aggregation run."]])])))
