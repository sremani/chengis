(ns chengis.web.views.environments
  "Environment management views — list, create/edit forms, lock/unlock."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- lock-badge [env]
  (if (:locked env)
    [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"}
     "Locked"]
    [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"}
     "Unlocked"]))

(defn- approval-badge [env]
  (when (:requires-approval env)
    [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"}
     "Requires Approval"]))

(defn- env-card [env csrf-token]
  [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 border-l-4"
         :style (str "border-left-color: "
                     (case (:env-order env)
                       10 "#10B981"   ;; green for dev
                       20 "#F59E0B"   ;; yellow for staging
                       30 "#EF4444"   ;; red for prod
                       "#6B7280"))}   ;; gray default
   [:div {:class "flex justify-between items-start mb-3"}
    [:div
     [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-white"}
      (escape-html (str (:name env)))]
     [:p {:class "text-sm text-gray-500 dark:text-gray-400"}
      (escape-html (str (:slug env)))
      " · Order: " (:env-order env)]]
    [:div {:class "flex gap-2"}
     (lock-badge env)
     (approval-badge env)]]
   (when (:description env)
     [:p {:class "text-sm text-gray-600 dark:text-gray-300 mb-3"}
      (escape-html (str (:description env)))])
   [:div {:class "flex gap-2 mt-4"}
    [:a {:href (str "/admin/environments/" (:id env))
         :class "text-sm text-blue-600 hover:text-blue-800 dark:text-blue-400"}
     "Details"]
    (if (:locked env)
      [:form {:method "POST" :action (str "/admin/environments/" (:id env) "/unlock")
              :class "inline"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:button {:type "submit"
                 :class "text-sm text-green-600 hover:text-green-800 dark:text-green-400"}
        "Unlock"]]
      [:form {:method "POST" :action (str "/admin/environments/" (:id env) "/lock")
              :class "inline"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:button {:type "submit"
                 :class "text-sm text-red-600 hover:text-red-800 dark:text-red-400"}
        "Lock"]])]])

(defn- create-form [csrf-token]
  [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
   [:h2 {:class "text-lg font-semibold mb-4 text-gray-900 dark:text-white"} "Create Environment"]
   [:form {:method "POST" :action "/admin/environments"}
    [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
    [:div {:class "grid grid-cols-1 md:grid-cols-2 gap-4"}
     [:div
      [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Name"]
      [:input {:type "text" :name "name" :required true
               :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"
               :placeholder "Production"}]]
     [:div
      [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Slug"]
      [:input {:type "text" :name "slug" :required true
               :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"
               :placeholder "prod" :pattern "[a-z0-9-]+"}]]
     [:div
      [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Order"]
      [:input {:type "number" :name "env_order" :required true :value "10"
               :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}]]
     [:div
      [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Description"]
      [:input {:type "text" :name "description"
               :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}]]
     [:div {:class "flex items-center gap-4 col-span-2"}
      [:label {:class "flex items-center gap-2"}
       [:input {:type "checkbox" :name "requires_approval" :value "true"
                :class "rounded border-gray-300 text-blue-600"}]
       [:span {:class "text-sm text-gray-700 dark:text-gray-300"} "Requires Approval"]]
      [:label {:class "flex items-center gap-2"}
       [:input {:type "checkbox" :name "auto_promote" :value "true"
                :class "rounded border-gray-300 text-blue-600"}]
       [:span {:class "text-sm text-gray-700 dark:text-gray-300"} "Auto-Promote"]]]]
    [:button {:type "submit"
              :class "mt-4 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 text-sm font-medium"}
     "Create Environment"]]])

(defn render
  "Render the environments list page."
  [{:keys [environments csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title "Environments" :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       [:div {:class "flex justify-between items-center mb-6"}
        [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"}
         "Environments"]
        [:span {:class "text-sm text-gray-500 dark:text-gray-400"}
         (str (count environments) " environment(s)")]]
       (create-form csrf-token)
       [:div {:class "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6"}
        (for [env environments]
          (env-card env csrf-token))]]))))

(defn render-detail
  "Render a single environment detail page."
  [{:keys [environment current-artifact deployments csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title (str "Environment: " (:name environment)) :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       [:div {:class "flex justify-between items-center mb-6"}
        [:div
         [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"}
          (escape-html (str (:name environment)))]
         [:p {:class "text-sm text-gray-500 dark:text-gray-400"}
          (escape-html (str (:slug environment)))
          " · Order: " (:env-order environment)]]
        [:div {:class "flex gap-2"}
         (lock-badge environment)
         (approval-badge environment)]]
       (when (:description environment)
         [:p {:class "text-gray-600 dark:text-gray-300 mb-6"}
          (escape-html (str (:description environment)))])
       ;; Current deployment
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
        [:h2 {:class "text-lg font-semibold mb-3 text-gray-900 dark:text-white"} "Current Deployment"]
        (if current-artifact
          [:div
           [:p {:class "text-sm"} "Build: "
            [:a {:href (str "/builds/" (:build-id current-artifact))
                 :class "text-blue-600 hover:underline dark:text-blue-400"}
             (escape-html (str (:build-id current-artifact)))]]
           [:p {:class "text-sm text-gray-500 dark:text-gray-400"}
            "Deployed: " (escape-html (str (:deployed-at current-artifact)))]]
          [:p {:class "text-sm text-gray-500 dark:text-gray-400"} "No deployment yet"])]
       ;; Recent deployments
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6"}
        [:h2 {:class "text-lg font-semibold mb-3 text-gray-900 dark:text-white"} "Recent Deployments"]
        (if (seq deployments)
          [:div {:class "space-y-2"}
           (for [d deployments]
             [:div {:class "flex justify-between items-center py-2 border-b border-gray-100 dark:border-gray-700"}
              [:span {:class "text-sm"} (escape-html (str (:build-id d)))]
              [:span {:class (str "text-xs px-2 py-1 rounded "
                                  (case (:status d)
                                    "succeeded" "bg-green-100 text-green-800"
                                    "failed" "bg-red-100 text-red-800"
                                    "in-progress" "bg-blue-100 text-blue-800"
                                    "bg-gray-100 text-gray-800"))}
               (escape-html (str (:status d)))]])]
          [:p {:class "text-sm text-gray-500 dark:text-gray-400"} "No deployments yet"])]]))))
