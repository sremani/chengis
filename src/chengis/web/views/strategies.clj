(ns chengis.web.views.strategies
  "Deployment strategy views — list, create/edit forms with type-specific config."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- type-badge [strategy-type]
  (let [colors (case strategy-type
                 "direct" "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"
                 "blue-green" "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200"
                 "canary" "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
                 "rolling" "bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-200"
                 "bg-gray-100 text-gray-800")]
    [:span {:class (str "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium " colors)}
     (escape-html (str strategy-type))]))

(defn- strategy-card [s]
  [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6"}
   [:div {:class "flex justify-between items-start mb-3"}
    [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-white"}
     (escape-html (str (:name s)))]
    (type-badge (:strategy-type s))]
   (when (:description s)
     [:p {:class "text-sm text-gray-600 dark:text-gray-300 mb-3"}
      (escape-html (str (:description s)))])
   (when (:config s)
     [:div {:class "bg-gray-50 dark:bg-gray-900 rounded p-3 mt-2"}
      [:pre {:class "text-xs text-gray-600 dark:text-gray-400 whitespace-pre-wrap"}
       (escape-html (str (:config s)))]])])

(defn render
  "Render the strategies page."
  [{:keys [strategies csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title "Deployment Strategies" :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       [:div {:class "flex justify-between items-center mb-6"}
        [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"} "Deployment Strategies"]
        [:span {:class "text-sm text-gray-500 dark:text-gray-400"}
         (str (count strategies) " strategy(ies)")]]
       ;; Create form
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
        [:h2 {:class "text-lg font-semibold mb-4 text-gray-900 dark:text-white"} "Create Strategy"]
        [:form {:method "POST" :action "/deploy/strategies"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
         [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-4"}
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Name"]
           [:input {:type "text" :name "name" :required true
                    :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}]]
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Type"]
           [:select {:name "strategy_type" :required true
                     :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}
            [:option {:value "direct"} "Direct"]
            [:option {:value "blue-green"} "Blue-Green"]
            [:option {:value "canary"} "Canary"]
            [:option {:value "rolling"} "Rolling"]]]
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Description"]
           [:input {:type "text" :name "description"
                    :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}]]]
         [:button {:type "submit"
                   :class "mt-4 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 text-sm font-medium"}
          "Create Strategy"]]]
       ;; Strategy cards
       [:div {:class "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6"}
        (for [s strategies]
          (strategy-card s))]]))))
