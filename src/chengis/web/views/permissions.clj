(ns chengis.web.views.permissions
  "Admin views for fine-grained permission management."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [hiccup2.core :as h]))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(def ^:private resource-types
  "Available resource types for permission grants."
  ["pipeline" "job" "secret" "agent" "artifact" "environment"])

(def ^:private actions
  "Available actions for permission grants."
  ["read" "write" "execute" "delete" "admin"])

(def ^:private th-class
  "Standard table header cell class."
  "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider")

(def ^:private td-class
  "Standard table data cell class."
  "px-4 py-3 text-sm text-gray-700")

;; ---------------------------------------------------------------------------
;; Direct permissions page
;; ---------------------------------------------------------------------------

(defn render-permissions-page
  "Main permissions admin page showing direct grants, grant form, and link to groups."
  [{:keys [permissions groups users csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title "Permissions" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Fine-Grained Permissions"
      [:a {:href "/admin"
           :class "text-sm text-gray-500 hover:text-gray-700"}
       "Back to Admin"])

    ;; Quick links
    [:div {:class "flex gap-3 mb-6"}
     [:a {:href "/admin/permissions/groups"
          :class "px-4 py-2 bg-blue-100 rounded text-sm font-medium text-blue-700 hover:bg-blue-200 transition"}
      "Permission Groups"]
     [:span {:class "text-sm text-gray-400 self-center"}
      (str (count permissions) " direct permissions, " (count groups) " groups")]]

    ;; Grant permission form
    [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Grant Permission"]]
     [:div {:class "p-5"}
      [:form {:method "POST" :action "/admin/permissions/grant" :class "grid grid-cols-1 md:grid-cols-6 gap-4 items-end"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "User"]
        [:select {:name "user-id"
                  :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                  :required true}
         [:option {:value ""} "Select user..."]
         (for [u users]
           [:option {:value (:id u)} (:username u)])]]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Resource Type"]
        [:select {:name "resource-type"
                  :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                  :required true}
         [:option {:value ""} "Select type..."]
         (for [rt resource-types]
           [:option {:value rt} rt])]]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Resource ID"]
        [:input {:type "text" :name "resource-id"
                 :placeholder "e.g. my-pipeline"
                 :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                 :required true}]]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Action"]
        [:select {:name "action"
                  :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                  :required true}
         [:option {:value ""} "Select action..."]
         (for [a actions]
           [:option {:value a} a])]]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Expires At"]
        [:input {:type "datetime-local" :name "expires-at"
                 :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"}]]
       [:div
        [:button {:type "submit"
                  :class "w-full bg-blue-600 text-white px-4 py-2 rounded-md text-sm font-medium
                          hover:bg-blue-700 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500"}
         "Grant"]]]]]

    ;; Permissions table
    [:div {:class "bg-white rounded-lg shadow-sm border"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Direct Permissions"]]
     (if (empty? permissions)
       [:div {:class "p-5 text-center text-gray-400"}
        [:p "No direct permissions granted yet."]]
       [:div {:class "overflow-x-auto"}
        [:table {:class "min-w-full divide-y divide-gray-200"}
         [:thead {:class "bg-gray-50"}
          [:tr
           [:th {:class th-class} "User"]
           [:th {:class th-class} "Resource Type"]
           [:th {:class th-class} "Resource ID"]
           [:th {:class th-class} "Action"]
           [:th {:class th-class} "Granted By"]
           [:th {:class th-class} "Created At"]
           [:th {:class th-class} "Expires At"]
           [:th {:class th-class} ""]]]
         [:tbody {:class "bg-white divide-y divide-gray-200"}
          (for [perm permissions]
            [:tr {:class "hover:bg-gray-50 transition-colors"}
             [:td {:class td-class} (:user-id perm)]
             [:td {:class td-class}
              [:span {:class "font-mono bg-gray-100 px-2 py-0.5 rounded text-xs"}
               (:resource-type perm)]]
             [:td {:class td-class} (:resource-id perm)]
             [:td {:class td-class}
              [:span {:class "font-mono bg-blue-50 text-blue-700 px-2 py-0.5 rounded text-xs"}
               (:action perm)]]
             [:td {:class td-class} (or (:granted-by perm) "-")]
             [:td {:class (str td-class " font-mono text-xs")} (or (:created-at perm) "-")]
             [:td {:class (str td-class " font-mono text-xs")} (or (:expires-at perm) "Never")]
             [:td {:class td-class}
              [:form {:method "POST" :action (str "/admin/permissions/revoke/" (:id perm)) :class "inline"}
               [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
               [:button {:type "submit"
                         :class "text-red-600 hover:text-red-800 text-xs font-medium"
                         :onclick "return confirm('Revoke this permission?')"}
                "Revoke"]]]])]]])]))

;; ---------------------------------------------------------------------------
;; Groups list page
;; ---------------------------------------------------------------------------

(defn render-groups-page
  "Permission groups list page."
  [{:keys [groups csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title "Permission Groups" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Permission Groups"
      [:a {:href "/admin/permissions"
           :class "text-sm text-gray-500 hover:text-gray-700"}
       "Back to Permissions"])

    ;; Create group form
    [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Create Group"]]
     [:div {:class "p-5"}
      [:form {:method "POST" :action "/admin/permissions/groups" :class "grid grid-cols-1 md:grid-cols-3 gap-4 items-end"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Name"]
        [:input {:type "text" :name "name"
                 :placeholder "e.g. backend-devs"
                 :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                 :required true}]]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Description"]
        [:input {:type "text" :name "description"
                 :placeholder "Optional description"
                 :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"}]]
       [:div
        [:button {:type "submit"
                  :class "w-full bg-blue-600 text-white px-4 py-2 rounded-md text-sm font-medium
                          hover:bg-blue-700 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500"}
         "Create Group"]]]]]

    ;; Groups table
    [:div {:class "bg-white rounded-lg shadow-sm border"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Groups"]]
     (if (empty? groups)
       [:div {:class "p-5 text-center text-gray-400"}
        [:p "No permission groups created yet."]]
       [:div {:class "overflow-x-auto"}
        [:table {:class "min-w-full divide-y divide-gray-200"}
         [:thead {:class "bg-gray-50"}
          [:tr
           [:th {:class th-class} "Name"]
           [:th {:class th-class} "Description"]
           [:th {:class th-class} "Created By"]
           [:th {:class th-class} "Created At"]
           [:th {:class th-class} ""]]]
         [:tbody {:class "bg-white divide-y divide-gray-200"}
          (for [group groups]
            [:tr {:class "hover:bg-gray-50 transition-colors"}
             [:td {:class td-class}
              [:a {:href (str "/admin/permissions/groups/" (:id group))
                   :class "text-blue-600 hover:text-blue-800 hover:underline font-medium"}
               (:name group)]]
             [:td {:class td-class} (or (:description group) "-")]
             [:td {:class td-class} (or (:created-by group) "-")]
             [:td {:class (str td-class " font-mono text-xs")} (or (:created-at group) "-")]
             [:td {:class td-class}
              [:form {:method "POST"
                      :action (str "/admin/permissions/groups/" (:id group) "/delete")
                      :class "inline"}
               [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
               [:button {:type "submit"
                         :class "text-red-600 hover:text-red-800 text-xs font-medium"
                         :onclick "return confirm('Delete this group and all its entries/members?')"}
                "Delete"]]]])]]])]))

;; ---------------------------------------------------------------------------
;; Group detail page
;; ---------------------------------------------------------------------------

(defn render-group-detail-page
  "Single group detail page showing entries and members."
  [{:keys [group entries members users csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title (str "Group: " (:name group)) :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header (str "Group: " (:name group))
      [:a {:href "/admin/permissions/groups"
           :class "text-sm text-gray-500 hover:text-gray-700"}
       "Back to Groups"])

    ;; Group info
    [:div {:class "bg-white rounded-lg shadow-sm border mb-6 p-5"}
     [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 text-sm"}
      [:div
       [:span {:class "text-gray-500 block"} "Name"]
       [:span {:class "font-semibold"} (:name group)]]
      [:div
       [:span {:class "text-gray-500 block"} "Description"]
       [:span (or (:description group) "-")]]
      [:div
       [:span {:class "text-gray-500 block"} "Created By"]
       [:span (or (:created-by group) "-")]]
      [:div
       [:span {:class "text-gray-500 block"} "Entries / Members"]
       [:span {:class "font-semibold"} (str (count entries) " / " (count members))]]]]

    ;; Add entry form
    [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Permission Entries"]]
     [:div {:class "p-5"}
      [:form {:method "POST"
              :action (str "/admin/permissions/groups/" (:id group) "/entries")
              :class "grid grid-cols-1 md:grid-cols-4 gap-4 items-end mb-4"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Resource Type"]
        [:select {:name "resource-type"
                  :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                  :required true}
         [:option {:value ""} "Select type..."]
         (for [rt resource-types]
           [:option {:value rt} rt])]]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Resource ID"]
        [:input {:type "text" :name "resource-id"
                 :placeholder "e.g. my-pipeline"
                 :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                 :required true}]]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Action"]
        [:select {:name "action"
                  :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                  :required true}
         [:option {:value ""} "Select action..."]
         (for [a actions]
           [:option {:value a} a])]]
       [:div
        [:button {:type "submit"
                  :class "w-full bg-green-600 text-white px-4 py-2 rounded-md text-sm font-medium
                          hover:bg-green-700 transition-colors focus:outline-none focus:ring-2 focus:ring-green-500"}
         "Add Entry"]]]

      ;; Entries table
      (if (empty? entries)
        [:div {:class "text-center text-gray-400 py-4"}
         [:p "No entries in this group yet."]]
        [:table {:class "min-w-full divide-y divide-gray-200"}
         [:thead {:class "bg-gray-50"}
          [:tr
           [:th {:class th-class} "Resource Type"]
           [:th {:class th-class} "Resource ID"]
           [:th {:class th-class} "Action"]
           [:th {:class th-class} ""]]]
         [:tbody {:class "bg-white divide-y divide-gray-200"}
          (for [entry entries]
            [:tr {:class "hover:bg-gray-50 transition-colors"}
             [:td {:class td-class}
              [:span {:class "font-mono bg-gray-100 px-2 py-0.5 rounded text-xs"}
               (:resource-type entry)]]
             [:td {:class td-class} (:resource-id entry)]
             [:td {:class td-class}
              [:span {:class "font-mono bg-blue-50 text-blue-700 px-2 py-0.5 rounded text-xs"}
               (:action entry)]]
             [:td {:class td-class}
              [:form {:method "POST"
                      :action (str "/admin/permissions/groups/" (:id group) "/entries/" (:id entry) "/remove")
                      :class "inline"}
               [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
               [:button {:type "submit"
                         :class "text-red-600 hover:text-red-800 text-xs font-medium"
                         :onclick "return confirm('Remove this entry?')"}
                "Remove"]]]])]])]]

    ;; Add member form
    [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Members"]]
     [:div {:class "p-5"}
      [:form {:method "POST"
              :action (str "/admin/permissions/groups/" (:id group) "/members")
              :class "grid grid-cols-1 md:grid-cols-2 gap-4 items-end mb-4"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:div
        [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "User"]
        [:select {:name "user-id"
                  :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                  :required true}
         [:option {:value ""} "Select user..."]
         (for [u users]
           [:option {:value (:id u)} (:username u)])]]
       [:div
        [:button {:type "submit"
                  :class "w-full bg-green-600 text-white px-4 py-2 rounded-md text-sm font-medium
                          hover:bg-green-700 transition-colors focus:outline-none focus:ring-2 focus:ring-green-500"}
         "Add Member"]]]

      ;; Members table
      (if (empty? members)
        [:div {:class "text-center text-gray-400 py-4"}
         [:p "No members in this group yet."]]
        [:table {:class "min-w-full divide-y divide-gray-200"}
         [:thead {:class "bg-gray-50"}
          [:tr
           [:th {:class th-class} "User"]
           [:th {:class th-class} "Assigned By"]
           [:th {:class th-class} "Joined At"]
           [:th {:class th-class} ""]]]
         [:tbody {:class "bg-white divide-y divide-gray-200"}
          (for [member members]
            [:tr {:class "hover:bg-gray-50 transition-colors"}
             [:td {:class td-class} (:user-id member)]
             [:td {:class td-class} (or (:assigned-by member) "-")]
             [:td {:class (str td-class " font-mono text-xs")} (or (:created-at member) "-")]
             [:td {:class td-class}
              [:form {:method "POST"
                      :action (str "/admin/permissions/groups/" (:id group) "/members/" (:user-id member) "/remove")
                      :class "inline"}
               [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
               [:button {:type "submit"
                         :class "text-red-600 hover:text-red-800 text-xs font-medium"
                         :onclick "return confirm('Remove this member?')"}
                "Remove"]]]])]])]]))
