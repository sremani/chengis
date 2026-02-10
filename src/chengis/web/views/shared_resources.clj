(ns chengis.web.views.shared-resources
  "Admin views for cross-organization resource sharing."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]))

;; ---------------------------------------------------------------------------
;; Table helpers
;; ---------------------------------------------------------------------------

(def ^:private th-class
  "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider")

(def ^:private td-class
  "px-4 py-3 text-sm text-gray-600")

;; ---------------------------------------------------------------------------
;; Sections
;; ---------------------------------------------------------------------------

(defn- grants-from-section
  "Table of grants this org has shared with others."
  [grants-from csrf-token]
  [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
   [:div {:class "px-5 py-4 border-b"}
    [:h2 {:class "text-lg font-semibold text-gray-900"} "Shared by this Org"]]
   [:div {:class "p-5"}
    (if (empty? grants-from)
      [:div {:class "text-center py-8 text-gray-400"}
       [:p "No resources shared by this organization yet."]]
      [:div {:class "overflow-hidden border rounded-lg"}
       [:table {:class "min-w-full divide-y divide-gray-200"}
        [:thead {:class "bg-gray-50"}
         [:tr
          [:th {:class th-class} "Resource Type"]
          [:th {:class th-class} "Resource ID"]
          [:th {:class th-class} "Target Org"]
          [:th {:class th-class} "Granted By"]
          [:th {:class th-class} "Expires"]
          [:th {:class th-class} "Actions"]]]
        [:tbody {:class "bg-white divide-y divide-gray-200"}
         (for [grant grants-from]
           [:tr {:class "hover:bg-gray-50 transition-colors"}
            [:td {:class td-class}
             [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800"}
              (:resource-type grant)]]
            [:td {:class (str td-class " font-mono")} (:resource-id grant)]
            [:td {:class td-class} (:target-org-id grant)]
            [:td {:class td-class} (or (:granted-by grant) "-")]
            [:td {:class td-class} (or (:expires-at grant) "Never")]
            [:td {:class td-class}
             [:form {:method "POST" :action (str "/admin/shared-resources/revoke/" (:id grant))
                     :class "inline"}
              [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
              [:button {:type "submit"
                        :class "text-red-600 hover:text-red-800 text-sm font-medium transition"
                        :onclick "return confirm('Revoke this shared resource grant?')"}
               "Revoke"]]]])]]])]])

(defn- grants-to-section
  "Table of grants shared with this org from others."
  [grants-to]
  [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
   [:div {:class "px-5 py-4 border-b"}
    [:h2 {:class "text-lg font-semibold text-gray-900"} "Shared with this Org"]]
   [:div {:class "p-5"}
    (if (empty? grants-to)
      [:div {:class "text-center py-8 text-gray-400"}
       [:p "No resources shared with this organization yet."]]
      [:div {:class "overflow-hidden border rounded-lg"}
       [:table {:class "min-w-full divide-y divide-gray-200"}
        [:thead {:class "bg-gray-50"}
         [:tr
          [:th {:class th-class} "Resource Type"]
          [:th {:class th-class} "Resource ID"]
          [:th {:class th-class} "Source Org"]
          [:th {:class th-class} "Granted By"]
          [:th {:class th-class} "Expires"]]]
        [:tbody {:class "bg-white divide-y divide-gray-200"}
         (for [grant grants-to]
           [:tr {:class "hover:bg-gray-50 transition-colors"}
            [:td {:class td-class}
             [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800"}
              (:resource-type grant)]]
            [:td {:class (str td-class " font-mono")} (:resource-id grant)]
            [:td {:class td-class} (:source-org-id grant)]
            [:td {:class td-class} (or (:granted-by grant) "-")]
            [:td {:class td-class} (or (:expires-at grant) "Never")]])]]])]])

(defn- create-grant-form
  "Form to create a new cross-org resource sharing grant."
  [organizations csrf-token]
  [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
   [:div {:class "px-5 py-4 border-b"}
    [:h2 {:class "text-lg font-semibold text-gray-900"} "Create Grant"]]
   [:div {:class "p-5"}
    [:form {:method "POST" :action "/admin/shared-resources/grant" :class "space-y-4"}
     [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]

     ;; Target org dropdown
     [:div
      [:label {:for "target-org-id" :class "block text-sm font-medium text-gray-700 mb-1"}
       "Target Organization"]
      [:select {:name "target-org-id" :id "target-org-id"
                :class "w-full border-gray-300 rounded-md shadow-sm text-sm
                        focus:ring-blue-500 focus:border-blue-500 border px-3 py-2"
                :required true}
       [:option {:value ""} "Select an organization..."]
       (for [org organizations]
         [:option {:value (:id org)} (str (:name org) " (" (:slug org) ")")])]]

     ;; Resource type dropdown
     [:div
      [:label {:for "resource-type" :class "block text-sm font-medium text-gray-700 mb-1"}
       "Resource Type"]
      [:select {:name "resource-type" :id "resource-type"
                :class "w-full border-gray-300 rounded-md shadow-sm text-sm
                        focus:ring-blue-500 focus:border-blue-500 border px-3 py-2"
                :required true}
       [:option {:value ""} "Select a type..."]
       [:option {:value "agent-label"} "Agent Label"]
       [:option {:value "template"} "Template"]]]

     ;; Resource ID text input
     [:div
      [:label {:for "resource-id" :class "block text-sm font-medium text-gray-700 mb-1"}
       "Resource ID"]
      [:input {:type "text" :name "resource-id" :id "resource-id"
               :placeholder "e.g. docker, linux, my-template-id"
               :class "w-full border-gray-300 rounded-md shadow-sm text-sm
                       focus:ring-blue-500 focus:border-blue-500 border px-3 py-2"
               :required true}]]

     ;; Optional expiry date
     [:div
      [:label {:for "expires-at" :class "block text-sm font-medium text-gray-700 mb-1"}
       "Expires At "
       [:span {:class "text-gray-400 font-normal"} "(optional)"]]
      [:input {:type "datetime-local" :name "expires-at" :id "expires-at"
               :class "w-full border-gray-300 rounded-md shadow-sm text-sm
                       focus:ring-blue-500 focus:border-blue-500 border px-3 py-2"}]]

     ;; Submit button
     [:div
      [:button {:type "submit"
                :class "bg-blue-600 text-white px-4 py-2 rounded-md text-sm font-medium
                        hover:bg-blue-700 active:bg-blue-800 transition-colors
                        focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"}
       "Grant Access"]]]]])

;; ---------------------------------------------------------------------------
;; Main page
;; ---------------------------------------------------------------------------

(defn render-shared-resources-page
  "Main sharing admin page showing grants from/to the current org,
   plus a form to create new grants."
  [{:keys [grants-from grants-to organizations csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title "Shared Resources" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Cross-Org Shared Resources")

    ;; Shared by this org
    (grants-from-section grants-from csrf-token)

    ;; Shared with this org
    (grants-to-section grants-to)

    ;; Create grant form
    (create-grant-form organizations csrf-token)))
