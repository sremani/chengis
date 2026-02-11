(ns chengis.web.views.promotions
  "Artifact promotion views — pipeline visualization, promote form, history."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- status-badge [status]
  (let [colors (case status
                 "pending" "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
                 "approved" "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200"
                 "promoted" "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"
                 "rejected" "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"
                 "bg-gray-100 text-gray-800")]
    [:span {:class (str "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium " colors)}
     (escape-html (str status))]))

(defn- env-pipeline-card [env current-artifact]
  [:div {:class "flex-1 bg-white dark:bg-gray-800 rounded-lg shadow p-4 text-center min-w-0"
         :style (str "border-top: 3px solid "
                     (case (:env-order env)
                       10 "#10B981"
                       20 "#F59E0B"
                       30 "#EF4444"
                       "#6B7280"))}
   [:h3 {:class "font-semibold text-gray-900 dark:text-white text-sm"}
    (escape-html (str (:name env)))]
   (if current-artifact
     [:div {:class "mt-2"}
      [:p {:class "text-xs text-gray-500 dark:text-gray-400 font-mono truncate"}
       (escape-html (str (:build-id current-artifact)))]
      [:p {:class "text-xs text-gray-400 dark:text-gray-500 mt-1"}
       (escape-html (str (:deployed-at current-artifact)))]]
     [:p {:class "text-xs text-gray-400 dark:text-gray-500 mt-2"} "No deployment"])])

(defn- arrow []
  [:div {:class "flex items-center px-2 text-gray-400 dark:text-gray-500"}
   [:svg {:class "w-6 h-6" :fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
    [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M9 5l7 7-7 7"}]]])

(defn- promotion-row [p]
  [:tr {:class "border-b border-gray-100 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-800"}
   [:td {:class "py-3 px-4 text-sm font-mono"} (escape-html (subs (str (:id p)) 0 (min 8 (count (str (:id p))))))]
   [:td {:class "py-3 px-4 text-sm font-mono"} (escape-html (str (:build-id p)))]
   [:td {:class "py-3 px-4"} (status-badge (:status p))]
   [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
    (escape-html (str (:promoted-by p)))]
   [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
    (escape-html (str (:created-at p)))]])

(defn render
  "Render the promotions page with pipeline visualization and history."
  [{:keys [environments env-artifacts promotions builds csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title "Artifact Promotions" :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white mb-6"} "Artifact Promotions"]
       ;; Pipeline visualization
       (when (seq environments)
         [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
          [:h2 {:class "text-lg font-semibold mb-4 text-gray-900 dark:text-white"} "Environment Pipeline"]
          [:div {:class "flex items-center gap-0 overflow-x-auto"}
           (interpose (arrow)
             (for [env environments]
               (env-pipeline-card env (get env-artifacts (:id env)))))]])
       ;; Promote form
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
        [:h2 {:class "text-lg font-semibold mb-4 text-gray-900 dark:text-white"} "Promote Artifact"]
        [:form {:method "POST" :action "/deploy/promotions"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
         [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-4"}
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Build"]
           [:select {:name "build_id" :required true
                     :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}
            [:option {:value ""} "Select build..."]
            (for [b (or builds [])]
              [:option {:value (:id b)} (str (:id b) " (" (:job-id b) ")")])]]
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "From Environment"]
           [:select {:name "from_environment_id"
                     :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}
            [:option {:value ""} "None (initial)"]
            (for [env (or environments [])]
              [:option {:value (:id env)} (escape-html (str (:name env)))])]]
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "To Environment"]
           [:select {:name "to_environment_id" :required true
                     :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}
            [:option {:value ""} "Select target..."]
            (for [env (or environments [])]
              [:option {:value (:id env)} (escape-html (str (:name env)))])]]]
         [:button {:type "submit"
                   :class "mt-4 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 text-sm font-medium"}
          "Promote"]]]
       ;; Promotion history
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow overflow-hidden"}
        [:table {:class "min-w-full divide-y divide-gray-200 dark:divide-gray-700"}
         [:thead {:class "bg-gray-50 dark:bg-gray-900"}
          [:tr
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "ID"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Build"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Status"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "By"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Created"]]]
         [:tbody
          (if (seq promotions)
            (for [p promotions] (promotion-row p))
            [:tr [:td {:colspan "5" :class "py-8 text-center text-gray-500 dark:text-gray-400"} "No promotions yet"]])]]]]))))
