(ns chengis.web.views.deployments
  "Deployment views — list with status, detail with step progress."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- status-badge [status]
  (let [colors (case status
                 "pending" "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"
                 "in-progress" "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200"
                 "succeeded" "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"
                 "failed" "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"
                 "cancelled" "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
                 "rolling-back" "bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200"
                 "rolled-back" "bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-200"
                 "bg-gray-100 text-gray-800")]
    [:span {:class (str "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium " colors)}
     (escape-html (str status))]))

(defn- deployment-row [d]
  [:tr {:class "border-b border-gray-100 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-800"}
   [:td {:class "py-3 px-4 text-sm"}
    [:a {:href (str "/deploy/deployments/" (:id d))
         :class "text-blue-600 hover:underline dark:text-blue-400 font-mono"}
     (escape-html (subs (str (:id d)) 0 (min 8 (count (str (:id d))))))]]
   [:td {:class "py-3 px-4 text-sm font-mono"} (escape-html (str (:build-id d)))]
   [:td {:class "py-3 px-4 text-sm"} (escape-html (str (:environment-id d)))]
   [:td {:class "py-3 px-4"} (status-badge (:status d))]
   [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
    (escape-html (str (:initiated-by d)))]
   [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
    (escape-html (str (:created-at d)))]])

(defn render
  "Render the deployments list page."
  [{:keys [deployments csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title "Deployments" :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       [:div {:class "flex justify-between items-center mb-6"}
        [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"} "Deployments"]
        [:span {:class "text-sm text-gray-500 dark:text-gray-400"}
         (str (count deployments) " deployment(s)")]]
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow overflow-hidden"}
        [:table {:class "min-w-full divide-y divide-gray-200 dark:divide-gray-700"}
         [:thead {:class "bg-gray-50 dark:bg-gray-900"}
          [:tr
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "ID"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Build"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Environment"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Status"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Initiated By"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Created"]]]
         [:tbody
          (if (seq deployments)
            (for [d deployments] (deployment-row d))
            [:tr [:td {:colspan "6" :class "py-8 text-center text-gray-500 dark:text-gray-400"} "No deployments yet"]])]]]]))))

(defn- step-progress [step]
  [:div {:class (str "flex items-center gap-3 py-2 px-3 rounded "
                     (case (:status step)
                       "succeeded" "bg-green-50 dark:bg-green-900/20"
                       "failed" "bg-red-50 dark:bg-red-900/20"
                       "in-progress" "bg-blue-50 dark:bg-blue-900/20"
                       "bg-gray-50 dark:bg-gray-800"))}
   [:div {:class (str "w-3 h-3 rounded-full "
                      (case (:status step)
                        "succeeded" "bg-green-500"
                        "failed" "bg-red-500"
                        "in-progress" "bg-blue-500 animate-pulse"
                        "bg-gray-300 dark:bg-gray-600"))}]
   [:span {:class "text-sm font-medium flex-1"} (escape-html (str (:step-name step)))]
   (status-badge (:status step))
   (when (:output step)
     [:span {:class "text-xs text-gray-500 dark:text-gray-400 truncate max-w-xs"}
      (escape-html (str (:output step)))])])

(defn render-detail
  "Render a single deployment detail page with step progress."
  [{:keys [deployment steps environment csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title (str "Deployment " (subs (str (:id deployment)) 0 (min 8 (count (str (:id deployment))))))
       :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       [:div {:class "flex justify-between items-center mb-6"}
        [:div
         [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"}
          "Deployment Details"]
         [:p {:class "text-sm text-gray-500 dark:text-gray-400 font-mono"}
          (escape-html (str (:id deployment)))]]
        [:div {:class "flex gap-2 items-center"}
         (status-badge (:status deployment))
         (when (and (not (#{"succeeded" "failed" "cancelled" "rolled-back"} (:status deployment))))
           [:form {:method "POST" :action (str "/deploy/deployments/" (:id deployment) "/cancel")}
            [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
            [:button {:type "submit" :class "px-3 py-1 bg-red-600 text-white rounded text-sm hover:bg-red-700"}
             "Cancel"]])
         (when (= "failed" (:status deployment))
           [:form {:method "POST" :action (str "/deploy/deployments/" (:id deployment) "/rollback")}
            [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
            [:button {:type "submit" :class "px-3 py-1 bg-orange-600 text-white rounded text-sm hover:bg-orange-700"}
             "Rollback"]])]]
       ;; Info
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
        [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 text-sm"}
         [:div
          [:span {:class "text-gray-500 dark:text-gray-400 block"} "Environment"]
          [:span {:class "font-medium"} (escape-html (str (or (:name environment) (:environment-id deployment))))]]
         [:div
          [:span {:class "text-gray-500 dark:text-gray-400 block"} "Build"]
          [:span {:class "font-medium font-mono"}
           [:a {:href (str "/builds/" (:build-id deployment)) :class "text-blue-600 hover:underline dark:text-blue-400"}
            (escape-html (str (:build-id deployment)))]]]
         [:div
          [:span {:class "text-gray-500 dark:text-gray-400 block"} "Initiated By"]
          [:span {:class "font-medium"} (escape-html (str (:initiated-by deployment)))]]
         [:div
          [:span {:class "text-gray-500 dark:text-gray-400 block"} "Started"]
          [:span {:class "font-medium"} (escape-html (str (:started-at deployment)))]]
         (when (:completed-at deployment)
           [:div
            [:span {:class "text-gray-500 dark:text-gray-400 block"} "Completed"]
            [:span {:class "font-medium"} (escape-html (str (:completed-at deployment)))]])
         (when (:rollback-of deployment)
           [:div
            [:span {:class "text-gray-500 dark:text-gray-400 block"} "Rollback Of"]
            [:span {:class "font-medium font-mono"} (escape-html (str (:rollback-of deployment)))]])]]
       ;; Steps
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6"}
        [:h2 {:class "text-lg font-semibold mb-4 text-gray-900 dark:text-white"} "Deployment Steps"]
        (if (seq steps)
          [:div {:class "space-y-2"}
           (for [step steps]
             (step-progress step))]
          [:p {:class "text-sm text-gray-500 dark:text-gray-400"} "No steps recorded"])]]))))
