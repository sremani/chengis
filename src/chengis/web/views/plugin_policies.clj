(ns chengis.web.views.plugin-policies
  "Admin page for managing plugin trust policies."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]))

(defn render
  "Plugin Policies admin page."
  [{:keys [policies csrf-token user auth-enabled flash]}]
  (layout/base-layout
    {:title "Plugin Policies" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Plugin Policies"
      [:a {:href "/admin"
           :class "text-sm text-gray-500 hover:text-gray-700"}
       "\u2190 Admin"])

    ;; Flash message
    (when flash
      [:div {:class "rounded-lg border p-4 mb-6 bg-blue-50 border-blue-200 text-blue-700"}
       flash])

    ;; Description
    [:div {:class "bg-amber-50 border border-amber-200 rounded-lg p-4 mb-6 text-sm text-amber-800"}
     [:strong "External Plugin Trust: "]
     "Only plugins with an explicit 'allowed' policy can be loaded from the plugins directory. "
     "Builtin plugins are always trusted and do not need policies."]

    ;; Add plugin policy form
    [:div {:class "bg-white rounded-lg shadow-sm border p-5 mb-6"}
     [:h2 {:class "text-lg font-semibold text-gray-900 mb-4"} "Allow Plugin"]
     [:form {:method "POST" :action "/admin/plugins/policies" :class "flex items-end gap-4"}
      [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
      [:div {:class "flex-1"}
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Plugin Name"]
       [:input {:type "text" :name "plugin-name" :required true
                :placeholder "e.g. my-custom-notifier"
                :class "w-full px-3 py-2 border border-gray-300 rounded-md text-sm
                        focus:outline-none focus:ring-2 focus:ring-blue-500"}]]
      [:div
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Trust Level"]
       [:select {:name "trust-level"
                 :class "px-3 py-2 border border-gray-300 rounded-md text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500"}
        [:option {:value "trusted"} "Trusted"]
        [:option {:value "reviewed"} "Reviewed"]]]
      [:button {:type "submit"
                :class "px-4 py-2 bg-green-600 text-white rounded-md text-sm font-medium
                        hover:bg-green-700 transition-colors"}
       "Allow Plugin"]]]

    ;; Policies table
    [:div {:class "bg-white rounded-lg shadow-sm border overflow-hidden"}
     [:table {:class "w-full text-sm"}
      [:thead
       [:tr {:class "bg-gray-50 text-left text-gray-600 border-b"}
        [:th {:class "py-3 px-4 font-medium"} "Plugin Name"]
        [:th {:class "py-3 px-4 font-medium"} "Trust Level"]
        [:th {:class "py-3 px-4 font-medium"} "Status"]
        [:th {:class "py-3 px-4 font-medium"} "Created By"]
        [:th {:class "py-3 px-4 font-medium"} "Actions"]]]
      [:tbody
       (if (empty? policies)
         [:tr
          [:td {:class "py-8 text-center text-gray-400" :colspan 5}
           "No plugin policies configured. All external plugins will be blocked."]]
         (for [policy policies]
           [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
            [:td {:class "py-3 px-4 font-mono"} (:plugin-name policy)]
            [:td {:class "py-3 px-4"}
             [:span {:class (str "px-2 py-0.5 rounded-full text-xs font-medium "
                                 (case (:trust-level policy)
                                   "trusted" "bg-green-100 text-green-700"
                                   "reviewed" "bg-blue-100 text-blue-700"
                                   "bg-gray-100 text-gray-700"))}
              (:trust-level policy)]]
            [:td {:class "py-3 px-4"}
             (if (:allowed policy)
               [:span {:class "text-green-600 font-medium"} "Allowed"]
               [:span {:class "text-red-600 font-medium"} "Blocked"])]
            [:td {:class "py-3 px-4 text-gray-500"} (or (:created-by policy) "\u2014")]
            [:td {:class "py-3 px-4"}
             [:form {:method "POST"
                     :action (str "/admin/plugins/policies/" (:plugin-name policy) "/delete")
                     :class "inline"}
              [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
              [:button {:type "submit"
                        :class "text-red-600 hover:text-red-800 text-xs font-medium"
                        :onclick "return confirm('Remove this plugin policy?')"}
               "Remove"]]]]))]]]))
