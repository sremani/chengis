(ns chengis.web.views.docker-policies
  "Admin page for managing Docker image policies."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]))

(defn render
  "Docker Policies admin page."
  [{:keys [policies csrf-token user auth-enabled flash]}]
  (layout/base-layout
    {:title "Docker Policies" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Docker Image Policies"
      [:a {:href "/admin"
           :class "text-sm text-gray-500 hover:text-gray-700"}
       "\u2190 Admin"])

    ;; Flash message
    (when flash
      [:div {:class "rounded-lg border p-4 mb-6 bg-blue-50 border-blue-200 text-blue-700"}
       flash])

    ;; Description
    [:div {:class "bg-amber-50 border border-amber-200 rounded-lg p-4 mb-6 text-sm text-amber-800"}
     [:strong "Docker Image Policy: "]
     "Control which Docker images can be used in builds. "
     "Policies are evaluated in priority order (lower = higher priority). "
     "First matching policy wins. When no policies exist, all images are allowed."]

    ;; Create policy form
    [:div {:class "bg-white rounded-lg shadow-sm border p-5 mb-6"}
     [:h2 {:class "text-lg font-semibold text-gray-900 mb-4"} "Add Policy"]
     [:form {:method "POST" :action "/admin/docker/policies"
             :class "grid grid-cols-1 md:grid-cols-5 gap-4 items-end"}
      [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
      [:div
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Type"]
       [:select {:name "policy-type"
                 :class "w-full px-3 py-2 border border-gray-300 rounded-md text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500"}
        [:option {:value "allowed-registry"} "Allowed Registry"]
        [:option {:value "denied-image"} "Denied Image"]
        [:option {:value "allowed-image"} "Allowed Image"]]]
      [:div
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Pattern"]
       [:input {:type "text" :name "pattern" :required true
                :placeholder "e.g. docker.io/* or myrepo/evil:*"
                :class "w-full px-3 py-2 border border-gray-300 rounded-md text-sm
                        focus:outline-none focus:ring-2 focus:ring-blue-500"}]]
      [:div
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Action"]
       [:select {:name "action"
                 :class "w-full px-3 py-2 border border-gray-300 rounded-md text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500"}
        [:option {:value "allow"} "Allow"]
        [:option {:value "deny"} "Deny"]]]
      [:div
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Priority"]
       [:input {:type "number" :name "priority" :value "100" :min "1" :max "9999"
                :class "w-full px-3 py-2 border border-gray-300 rounded-md text-sm
                        focus:outline-none focus:ring-2 focus:ring-blue-500"}]]
      [:div
       [:button {:type "submit"
                 :class "w-full px-4 py-2 bg-blue-600 text-white rounded-md text-sm font-medium
                         hover:bg-blue-700 transition-colors"}
        "Add Policy"]]]]

    ;; Policies table
    [:div {:class "bg-white rounded-lg shadow-sm border overflow-hidden"}
     [:table {:class "w-full text-sm"}
      [:thead
       [:tr {:class "bg-gray-50 text-left text-gray-600 border-b"}
        [:th {:class "py-3 px-4 font-medium"} "Priority"]
        [:th {:class "py-3 px-4 font-medium"} "Type"]
        [:th {:class "py-3 px-4 font-medium"} "Pattern"]
        [:th {:class "py-3 px-4 font-medium"} "Action"]
        [:th {:class "py-3 px-4 font-medium"} "Status"]
        [:th {:class "py-3 px-4 font-medium"} "Actions"]]]
      [:tbody
       (if (empty? policies)
         [:tr
          [:td {:class "py-8 text-center text-gray-400" :colspan 6}
           "No Docker policies configured. All images are allowed."]]
         (for [policy policies]
           [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
            [:td {:class "py-3 px-4 font-mono text-gray-500"} (:priority policy)]
            [:td {:class "py-3 px-4"}
             [:span {:class (str "px-2 py-0.5 rounded-full text-xs font-medium "
                                 (case (:policy-type policy)
                                   "allowed-registry" "bg-green-100 text-green-700"
                                   "denied-image" "bg-red-100 text-red-700"
                                   "allowed-image" "bg-blue-100 text-blue-700"
                                   "bg-gray-100 text-gray-700"))}
              (:policy-type policy)]]
            [:td {:class "py-3 px-4 font-mono"} (:pattern policy)]
            [:td {:class "py-3 px-4"}
             (if (= "allow" (:action policy))
               [:span {:class "text-green-600 font-medium"} "Allow"]
               [:span {:class "text-red-600 font-medium"} "Deny"])]
            [:td {:class "py-3 px-4"}
             (if (:enabled policy)
               [:span {:class "text-green-600"} "Enabled"]
               [:span {:class "text-gray-400"} "Disabled"])]
            [:td {:class "py-3 px-4"}
             [:form {:method "POST"
                     :action (str "/admin/docker/policies/" (:id policy) "/delete")
                     :class "inline"}
              [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
              [:button {:type "submit"
                        :class "text-red-600 hover:text-red-800 text-xs font-medium"
                        :onclick "return confirm('Delete this Docker policy?')"}
               "Delete"]]]]))]]]))
