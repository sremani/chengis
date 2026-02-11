(ns chengis.web.views.deploy-dashboard
  "Deployment dashboard — unified view of environments, recent deployments,
   pending promotions, and quick-deploy controls."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- env-status-card [env current-artifact recent-deployments]
  [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-5 border-l-4"
         :style (str "border-left-color: "
                     (case (:env-order env)
                       10 "#10B981"
                       20 "#F59E0B"
                       30 "#EF4444"
                       "#6B7280"))}
   [:div {:class "flex justify-between items-start mb-3"}
    [:div
     [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-white"}
      (escape-html (str (:name env)))]
     [:p {:class "text-xs text-gray-500 dark:text-gray-400"} (escape-html (str (:slug env)))]]
    [:div {:class "flex gap-1"}
     (when (:locked env)
       [:span {:class "px-2 py-0.5 rounded-full text-xs bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"}
        "Locked"])
     (when (:requires-approval env)
       [:span {:class "px-2 py-0.5 rounded-full text-xs bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"}
        "Approval"])]]
   ;; Current build
   (if current-artifact
     [:div {:class "mt-2"}
      [:p {:class "text-xs text-gray-500 dark:text-gray-400"} "Current:"]
      [:p {:class "text-sm font-mono text-gray-700 dark:text-gray-300 truncate"}
       (escape-html (str (:build-id current-artifact)))]
      [:p {:class "text-xs text-gray-400 mt-1"}
       (escape-html (str (:deployed-at current-artifact)))]]
     [:p {:class "text-xs text-gray-400 dark:text-gray-500 mt-2 italic"} "No deployment"])
   ;; Recent deployments mini-list
   (when (seq recent-deployments)
     [:div {:class "mt-3 border-t dark:border-gray-700 pt-2"}
      [:p {:class "text-xs text-gray-500 dark:text-gray-400 mb-1"} "Recent:"]
      (for [d (take 3 recent-deployments)]
        [:div {:class "flex justify-between items-center text-xs py-0.5"}
         [:span {:class (str "px-1 rounded "
                             (case (:status d)
                               "succeeded" "bg-green-100 text-green-700"
                               "failed" "bg-red-100 text-red-700"
                               "in-progress" "bg-blue-100 text-blue-700"
                               "bg-gray-100 text-gray-600"))}
          (escape-html (str (:status d)))]
         [:span {:class "text-gray-400"} (escape-html (str (:created-at d)))]])])
   [:div {:class "mt-3"}
    [:a {:href (str "/admin/environments/" (:id env))
         :class "text-xs text-blue-600 hover:underline dark:text-blue-400"}
     "View Details"]]])

(defn- deployment-timeline-item [d]
  [:div {:class "flex items-start gap-3 py-2"}
   [:div {:class (str "w-2.5 h-2.5 rounded-full mt-1.5 "
                      (case (:status d)
                        "succeeded" "bg-green-500"
                        "failed" "bg-red-500"
                        "in-progress" "bg-blue-500 animate-pulse"
                        "cancelled" "bg-yellow-500"
                        "bg-gray-400"))}]
   [:div {:class "flex-1 min-w-0"}
    [:div {:class "flex items-center gap-2"}
     [:span {:class "text-sm font-medium text-gray-900 dark:text-white truncate"}
      (escape-html (str (:build-id d)))]
     [:span {:class (str "text-xs px-1.5 py-0.5 rounded "
                         (case (:status d)
                           "succeeded" "bg-green-100 text-green-700"
                           "failed" "bg-red-100 text-red-700"
                           "in-progress" "bg-blue-100 text-blue-700"
                           "bg-gray-100 text-gray-600"))}
      (escape-html (str (:status d)))]]
    [:p {:class "text-xs text-gray-500 dark:text-gray-400"}
     (escape-html (str (:created-at d)))]]])

(defn render
  "Render the deployment dashboard overview."
  [{:keys [environments env-artifacts recent-deployments pending-promotions
           deployment-stats csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title "Deployment Dashboard" :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       [:div {:class "flex justify-between items-center mb-6"}
        [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"} "Deployment Dashboard"]
        [:div {:class "flex gap-3"}
         [:a {:href "/deploy/releases" :class "text-sm text-blue-600 hover:underline dark:text-blue-400"} "Releases"]
         [:a {:href "/deploy/promotions" :class "text-sm text-blue-600 hover:underline dark:text-blue-400"} "Promotions"]
         [:a {:href "/deploy/deployments" :class "text-sm text-blue-600 hover:underline dark:text-blue-400"} "Deployments"]
         [:a {:href "/deploy/strategies" :class "text-sm text-blue-600 hover:underline dark:text-blue-400"} "Strategies"]]]
       ;; Stats summary
       (when deployment-stats
         [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 mb-6"}
          (for [[label value color] [["Succeeded" (get deployment-stats "succeeded" 0) "text-green-600"]
                                     ["Failed" (get deployment-stats "failed" 0) "text-red-600"]
                                     ["In Progress" (get deployment-stats "in-progress" 0) "text-blue-600"]
                                     ["Pending" (get deployment-stats "pending" 0) "text-gray-600"]]]
            [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-4 text-center"}
             [:p {:class (str "text-2xl font-bold " color)} value]
             [:p {:class "text-xs text-gray-500 dark:text-gray-400 mt-1"} label]])])
       ;; Environment cards
       (when (seq environments)
         [:div {:class "mb-6"}
          [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-white mb-4"} "Environments"]
          [:div {:class "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6"}
           (for [env environments]
             (env-status-card env
               (get env-artifacts (:id env))
               (filter #(= (:environment-id %) (:id env)) (or recent-deployments []))))]])
       ;; Two-column: Recent deployments + Pending promotions
       [:div {:class "grid grid-cols-1 lg:grid-cols-2 gap-6"}
        ;; Recent deployment timeline
        [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6"}
         [:h2 {:class "text-lg font-semibold mb-4 text-gray-900 dark:text-white"} "Recent Deployments"]
         (if (seq recent-deployments)
           [:div {:class "space-y-1 divide-y divide-gray-100 dark:divide-gray-700"}
            (for [d (take 10 recent-deployments)]
              (deployment-timeline-item d))]
           [:p {:class "text-sm text-gray-500 dark:text-gray-400"} "No deployments yet"])]
        ;; Pending promotions
        [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6"}
         [:h2 {:class "text-lg font-semibold mb-4 text-gray-900 dark:text-white"} "Pending Promotions"]
         (if (seq pending-promotions)
           [:div {:class "space-y-3"}
            (for [p pending-promotions]
              [:div {:class "flex justify-between items-center py-2 px-3 bg-yellow-50 dark:bg-yellow-900/20 rounded"}
               [:div
                [:p {:class "text-sm font-mono"} (escape-html (str (:build-id p)))]
                [:p {:class "text-xs text-gray-500 dark:text-gray-400"}
                 "by " (escape-html (str (:promoted-by p)))]]
               [:div {:class "flex gap-2"}
                [:form {:method "POST" :action (str "/deploy/promotions/" (:id p) "/approve")}
                 [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
                 [:button {:type "submit" :class "px-2 py-1 bg-green-600 text-white rounded text-xs hover:bg-green-700"}
                  "Approve"]]
                [:form {:method "POST" :action (str "/deploy/promotions/" (:id p) "/reject")}
                 [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
                 [:button {:type "submit" :class "px-2 py-1 bg-red-600 text-white rounded text-xs hover:bg-red-700"}
                  "Reject"]]]])]
           [:p {:class "text-sm text-gray-500 dark:text-gray-400"} "No pending promotions"])]]
       ]))))
