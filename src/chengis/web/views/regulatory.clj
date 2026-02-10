(ns chengis.web.views.regulatory
  "Hiccup views for regulatory readiness dashboards."
  (:require [hiccup2.core :as h]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- status-icon [status]
  (case status
    "passing"      [:span {:class "text-green-600 font-bold"} "✓"]
    "failing"      [:span {:class "text-red-600 font-bold"} "✗"]
    "not-assessed" [:span {:class "text-gray-400"} "—"]
    [:span {:class "text-gray-400"} "?"]))

(defn- progress-bar [percentage]
  (let [color (cond
                (>= percentage 80) "bg-green-500"
                (>= percentage 50) "bg-yellow-500"
                :else "bg-red-500")]
    [:div {:class "w-full bg-gray-200 rounded-full h-3"}
     [:div {:class (str color " h-3 rounded-full transition-all")
            :style (str "width: " (min 100 (max 0 percentage)) "%")}]]))

;; ---------------------------------------------------------------------------
;; Framework detail panel
;; ---------------------------------------------------------------------------

(defn framework-detail-panel
  "Render controls for a single framework."
  [{:keys [framework checks score]}]
  [:div {:class "bg-white rounded-lg shadow-sm border p-5"}
   [:div {:class "flex items-center justify-between mb-4"}
    [:h3 {:class "text-lg font-semibold text-gray-900"}
     (case framework
       "soc2" "SOC 2 Type II"
       "iso27001" "ISO 27001"
       framework)]
    [:div {:class "text-right"}
     [:div {:class "text-2xl font-bold"}
      (str (int (or (:percentage score) 0)) "%")]
     [:div {:class "text-xs text-gray-500"}
      (str (:passing score) "/" (:total score) " controls passing")]]]

   (progress-bar (or (:percentage score) 0))

   [:table {:class "w-full text-sm mt-4"}
    [:thead
     [:tr {:class "text-left text-gray-500 border-b"}
      [:th {:class "py-2 w-8"}]
      [:th {:class "py-2 font-medium"} "Control"]
      [:th {:class "py-2 font-medium"} "Name"]
      [:th {:class "py-2 font-medium"} "Evidence"]]]
    [:tbody
     (for [check (sort-by :control-id checks)]
       [:tr {:class "border-b border-gray-100"}
        [:td {:class "py-2"} (status-icon (:status check))]
        [:td {:class "py-2 font-mono text-xs"} (:control-id check)]
        [:td {:class "py-2"} (:control-name check)]
        [:td {:class "py-2 text-xs text-gray-600"} (or (:evidence-summary check) "—")]])]]])

;; ---------------------------------------------------------------------------
;; Main regulatory dashboard
;; ---------------------------------------------------------------------------

(defn regulatory-dashboard
  "Render the regulatory readiness overview."
  [{:keys [frameworks csrf-token]}]
  [:div {:class "space-y-6"}
   [:div {:class "flex items-center justify-between"}
    [:h2 {:class "text-xl font-bold text-gray-900"} "Regulatory Readiness"]
    [:form {:method "POST" :action "/api/regulatory/assess" :class "inline"}
     (when csrf-token
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
     [:button {:type "submit"
               :class "px-4 py-2 bg-blue-600 text-white rounded text-sm hover:bg-blue-700"}
      "Reassess Now"]]]

   (if (empty? frameworks)
     [:div {:class "bg-white rounded-lg shadow p-8 text-center"}
      [:p {:class "text-gray-500"} "No regulatory assessments have been run yet."]
      [:p {:class "text-gray-400 text-sm mt-2"} "Click \"Reassess Now\" to evaluate compliance controls."]]
     (for [{:keys [framework checks score]} frameworks]
       (framework-detail-panel {:framework framework :checks checks :score score})))])
