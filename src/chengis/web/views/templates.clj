(ns chengis.web.views.templates
  "Admin pipeline templates management page."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [hiccup.util :refer [escape-html]]))

(defn- format-badge
  "Colored badge for template format."
  [format]
  (let [colors (case format
                 "edn"  "bg-purple-100 text-purple-800"
                 "yaml" "bg-blue-100 text-blue-800"
                 "bg-gray-100 text-gray-800")]
    [:span {:class (str "px-2 py-0.5 text-xs font-medium rounded " colors)}
     format]))

(defn- template-row
  "Single template row in the list."
  [template csrf-token]
  [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
   [:td {:class "py-3 px-4 text-sm font-medium"}
    (escape-html (:name template))]
   [:td {:class "py-3 px-4 text-sm text-gray-600"}
    (escape-html (or (:description template) "—"))]
   [:td {:class "py-3 px-4"}
    (format-badge (or (:format template) "edn"))]
   [:td {:class "py-3 px-4 text-sm text-center"}
    (str "v" (:version template))]
   [:td {:class "py-3 px-4 text-xs text-gray-400"}
    (:updated-at template)]
   [:td {:class "py-3 px-4"}
    [:div {:class "flex gap-2"}
     [:a {:href (str "/admin/templates/" (:name template) "/edit")
          :class "px-3 py-1 text-xs bg-blue-600 text-white rounded hover:bg-blue-700"}
      "Edit"]
     [:form {:method "POST" :action (str "/admin/templates/" (:id template) "/delete")
             :onsubmit "return confirm('Delete this template?')"}
      (when csrf-token
        [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
      [:button {:type "submit"
                :class "px-3 py-1 text-xs bg-red-600 text-white rounded hover:bg-red-700"}
       "Delete"]]]]])

(defn render
  "Render the templates list page."
  [{:keys [templates message error csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title "Pipeline Templates" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Pipeline Templates")

    ;; Flash messages
    (when message
      [:div {:class "mb-4 p-3 bg-green-100 text-green-800 rounded text-sm"}
       (escape-html message)])
    (when error
      [:div {:class "mb-4 p-3 bg-red-100 text-red-800 rounded text-sm"}
       (escape-html error)])

    [:div {:class "bg-white rounded-lg shadow-sm border overflow-hidden"}
     [:div {:class "px-5 py-3 border-b bg-gray-50 flex items-center justify-between"}
      [:span {:class "text-sm text-gray-600"}
       (str (count templates) " template(s)")]
      [:a {:href "/admin/templates/new"
           :class "px-3 py-1.5 text-sm bg-blue-600 text-white rounded hover:bg-blue-700"}
       "New Template"]]

     (if (empty? templates)
       [:div {:class "p-8 text-center text-gray-500"}
        "No templates defined yet. Create one to enable pipeline reuse."]
       [:table {:class "w-full text-sm"}
        [:thead
         [:tr {:class "text-left text-gray-500 border-b bg-gray-50"}
          [:th {:class "py-2 px-4 font-medium"} "Name"]
          [:th {:class "py-2 px-4 font-medium"} "Description"]
          [:th {:class "py-2 px-4 font-medium"} "Format"]
          [:th {:class "py-2 px-4 font-medium text-center"} "Version"]
          [:th {:class "py-2 px-4 font-medium"} "Updated"]
          [:th {:class "py-2 px-4 font-medium"} "Actions"]]]
        [:tbody
         (for [t templates]
           (template-row t csrf-token))]])]))

(defn render-form
  "Render the template create/edit form."
  [{:keys [template editing? error csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title (if editing? "Edit Template" "New Template")
     :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header (if editing? (str "Edit Template: " (:name template)) "New Template"))

    (when error
      [:div {:class "mb-4 p-3 bg-red-100 text-red-800 rounded text-sm"}
       (escape-html error)])

    [:div {:class "bg-white rounded-lg shadow-sm border p-6"}
     [:form {:method "POST"
             :action (if editing?
                       (str "/admin/templates/" (:name template) "/edit")
                       "/admin/templates")}
      (when csrf-token
        [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])

      [:div {:class "mb-4"}
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Name"]
       [:input {:type "text" :name "name"
                :value (or (:name template) "")
                :class "w-full px-3 py-2 border rounded text-sm"
                :placeholder "e.g., java-library"
                :required true
                :readonly (boolean editing?)}]]

      [:div {:class "mb-4"}
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Description"]
       [:input {:type "text" :name "description"
                :value (or (:description template) "")
                :class "w-full px-3 py-2 border rounded text-sm"
                :placeholder "Brief description of this template"}]]

      [:div {:class "mb-4"}
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Format"]
       [:select {:name "format" :class "px-3 py-2 border rounded text-sm"}
        [:option {:value "edn" :selected (= "edn" (or (:format template) "edn"))} "EDN"]
        [:option {:value "yaml" :selected (= "yaml" (:format template))} "YAML"]]]

      [:div {:class "mb-4"}
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Content"]
       [:textarea {:name "content"
                   :class "w-full px-3 py-2 border rounded text-sm font-mono"
                   :rows 15
                   :placeholder (str "{:stages [{:name \"Build\"\n"
                                     "          :steps [{:name \"Compile\" :run \"make build\"}]}]}")
                   :required true}
        (or (:content template) "")]]

      [:div {:class "flex gap-3"}
       [:button {:type "submit"
                 :class "px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 text-sm"}
        (if editing? "Update Template" "Create Template")]
       [:a {:href "/admin/templates"
            :class "px-4 py-2 bg-gray-200 text-gray-700 rounded hover:bg-gray-300 text-sm"}
        "Cancel"]]]]))
