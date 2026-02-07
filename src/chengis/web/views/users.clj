(ns chengis.web.views.users
  "User management view — admin-only."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- role-class [role]
  (case (str role)
    "admin" "bg-red-100 text-red-800"
    "developer" "bg-blue-100 text-blue-800"
    "viewer" "bg-gray-100 text-gray-800"
    "bg-gray-100 text-gray-800"))

(defn render
  "Render the user management page."
  [{:keys [users message error csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title "User Management" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    [:div {:class "space-y-6"}
     [:h1 {:class "text-2xl font-bold text-gray-900"} "User Management"]

     ;; Flash messages
     (when message
       [:div {:class "bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded"}
        (escape-html message)])
     (when error
       [:div {:class "bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded"}
        (escape-html error)])

     ;; Create user form
     [:div {:class "bg-white rounded-lg shadow p-6"}
      [:h2 {:class "text-lg font-semibold text-gray-800 mb-4"} "Create User"]
      [:form {:method "POST" :action "/admin/users" :class "flex gap-4 items-end"}
       (when csrf-token
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
       [:div
        [:label {:class "block text-xs font-medium text-gray-600 mb-1"} "Username"]
        [:input {:type "text" :name "username" :required true
                 :class "px-3 py-2 border border-gray-300 rounded text-sm w-40"
                 :placeholder "username"}]]
       [:div
        [:label {:class "block text-xs font-medium text-gray-600 mb-1"} "Password"]
        [:input {:type "password" :name "password" :required true
                 :class "px-3 py-2 border border-gray-300 rounded text-sm w-40"
                 :placeholder "password"}]]
       [:div
        [:label {:class "block text-xs font-medium text-gray-600 mb-1"} "Role"]
        [:select {:name "role"
                  :class "px-3 py-2 border border-gray-300 rounded text-sm"}
         [:option {:value "viewer"} "Viewer"]
         [:option {:value "developer"} "Developer"]
         [:option {:value "admin"} "Admin"]]]
       [:button {:type "submit"
                 :class "px-4 py-2 bg-blue-600 text-white rounded text-sm hover:bg-blue-700"}
        "Create User"]]]

     ;; Users table
     [:div {:class "bg-white rounded-lg shadow overflow-hidden"}
      [:table {:class "min-w-full divide-y divide-gray-200"}
       [:thead {:class "bg-gray-50"}
        [:tr
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Username"]
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Role"]
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Status"]
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Created"]
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Actions"]]]
       [:tbody {:class "bg-white divide-y divide-gray-200"}
        (for [u users]
          [:tr {:class (str "hover:bg-gray-50" (when (zero? (or (:active u) 1)) " opacity-50"))}
           [:td {:class "px-4 py-2 text-sm font-medium text-gray-900"}
            (escape-html (:username u))]
           [:td {:class "px-4 py-2 text-sm"}
            [:span {:class (str "inline-block px-2 py-0.5 rounded text-xs font-medium "
                                (role-class (:role u)))}
             (:role u)]]
           [:td {:class "px-4 py-2 text-sm"}
            (if (pos? (or (:active u) 1))
              [:span {:class "text-green-600"} "Active"]
              [:span {:class "text-red-600"} "Deactivated"])]
           [:td {:class "px-4 py-2 text-sm text-gray-500"} (or (:created-at u) "—")]
           [:td {:class "px-4 py-2 text-sm space-x-2"}
            ;; Role change form
            [:form {:method "POST" :action (str "/admin/users/" (:id u))
                    :class "inline-flex gap-1 items-center"}
             (when csrf-token
               [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
             [:select {:name "role" :class "px-1 py-0.5 border border-gray-300 rounded text-xs"}
              [:option {:value "viewer" :selected (= "viewer" (:role u))} "Viewer"]
              [:option {:value "developer" :selected (= "developer" (:role u))} "Developer"]
              [:option {:value "admin" :selected (= "admin" (:role u))} "Admin"]]
             [:button {:type "submit"
                       :class "px-2 py-0.5 bg-gray-200 rounded text-xs hover:bg-gray-300"}
              "Set"]]
            ;; Toggle active/deactivate
            [:form {:method "POST" :action (str "/admin/users/" (:id u) "/toggle")
                    :class "inline"}
             (when csrf-token
               [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
             [:button {:type "submit"
                       :class (str "px-2 py-0.5 rounded text-xs "
                                   (if (pos? (or (:active u) 1))
                                     "bg-red-100 text-red-700 hover:bg-red-200"
                                     "bg-green-100 text-green-700 hover:bg-green-200"))}
              (if (pos? (or (:active u) 1)) "Deactivate" "Reactivate")]]
            ;; Reset password
            [:form {:method "POST" :action (str "/admin/users/" (:id u) "/password")
                    :class "inline-flex gap-1 items-center"}
             (when csrf-token
               [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
             [:input {:type "password" :name "new-password" :placeholder "new pwd"
                      :class "px-1 py-0.5 border border-gray-300 rounded text-xs w-20"}]
             [:button {:type "submit"
                       :class "px-2 py-0.5 bg-yellow-100 text-yellow-800 rounded text-xs hover:bg-yellow-200"}
              "Reset"]]]])]]]]))
