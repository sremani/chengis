(ns chengis.web.views.policies
  "Policy management UI: list, create, edit, evaluation history."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [clojure.data.json :as json]))

(def ^:private policy-types
  [["branch-restriction" "Branch Restriction" "Allow or deny builds based on git branch patterns"]
   ["required-approval" "Required Approval" "Override approval requirements for matching stages"]
   ["author-restriction" "Author Restriction" "Allow or deny builds based on git author"]
   ["time-window" "Time Window" "Restrict builds to specific time windows"]
   ["parameter-restriction" "Parameter Restriction" "Check build parameters against conditions"]])

(def ^:private rule-placeholders
  {"branch-restriction"    "{\"branches\": [\"main\", \"release/*\"], \"action\": \"allow\"}"
   "required-approval"     "{\"stages\": [\"deploy-*\"], \"min_approvals\": 2, \"approver_group\": [\"admin1\"]}"
   "author-restriction"    "{\"authors\": [\"bot-*\"], \"action\": \"deny\"}"
   "time-window"           "{\"timezone\": \"America/New_York\", \"days\": [\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"], \"start_hour\": 9, \"end_hour\": 17, \"action\": \"allow-only\"}"
   "parameter-restriction" "{\"parameter\": \"force\", \"operator\": \"equals\", \"value\": \"true\", \"action\": \"deny\"}"})

(defn- enabled-badge [enabled]
  (if enabled
    [:span {:class "px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800"} "Enabled"]
    [:span {:class "px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-500"} "Disabled"]))

(defn- result-badge [result]
  (case result
    "allow" [:span {:class "px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800"} "Allow"]
    "deny" [:span {:class "px-2 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800"} "Deny"]
    "override-approval" [:span {:class "px-2 py-0.5 rounded-full text-xs font-medium bg-yellow-100 text-yellow-800"} "Override"]
    "error" [:span {:class "px-2 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800"} "Error"]
    [:span {:class "px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-800"} (or result "Unknown")]))

(defn render
  "Policy list page."
  [{:keys [policies csrf-token user auth-enabled message error]}]
  (layout/base-layout
    {:title "Policies" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Policy Engine")

    (when message
      [:div {:class "rounded-lg border p-4 mb-6 bg-green-50 border-green-200 text-green-700"}
       message])
    (when error
      [:div {:class "rounded-lg border p-4 mb-6 bg-red-50 border-red-200 text-red-700"}
       error])

    [:div {:class "flex justify-between items-center mb-4"}
     [:h2 {:class "text-lg font-semibold text-gray-900"} "Policies"]
     [:a {:href "/admin/policies/new"
          :class "bg-blue-600 text-white px-4 py-2 rounded text-sm font-medium hover:bg-blue-700 transition-colors"}
      "Create Policy"]]

    (if (empty? policies)
      [:div {:class "bg-white rounded-lg shadow-sm border p-8 text-center text-gray-500"}
       "No policies configured. Create your first policy to enforce governance rules."]
      [:div {:class "bg-white rounded-lg shadow-sm border overflow-hidden"}
       [:table {:class "w-full text-sm"}
        [:thead
         [:tr {:class "text-left text-gray-500 border-b bg-gray-50"}
          [:th {:class "py-3 px-4 font-medium"} "Name"]
          [:th {:class "py-3 px-4 font-medium"} "Type"]
          [:th {:class "py-3 px-4 font-medium"} "Priority"]
          [:th {:class "py-3 px-4 font-medium"} "Status"]
          [:th {:class "py-3 px-4 font-medium"} "Actions"]]]
        [:tbody
         (for [p policies]
           [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
            [:td {:class "py-3 px-4"}
             [:div {:class "font-medium text-gray-900"} (:name p)]
             (when (:description p)
               [:div {:class "text-xs text-gray-500"} (:description p)])]
            [:td {:class "py-3 px-4 text-gray-600"} (:policy-type p)]
            [:td {:class "py-3 px-4 text-gray-600"} (:priority p)]
            [:td {:class "py-3 px-4"} (enabled-badge (:enabled p))]
            [:td {:class "py-3 px-4"}
             [:div {:class "flex items-center gap-2"}
              [:a {:href (str "/admin/policies/" (:id p) "/edit")
                   :class "text-blue-600 hover:underline text-xs"} "Edit"]
              [:form {:method "POST" :action (str "/admin/policies/" (:id p) "/toggle")
                      :class "inline"}
               [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
               [:button {:type "submit"
                         :class "text-xs text-gray-600 hover:text-gray-900"}
                (if (:enabled p) "Disable" "Enable")]]
              [:form {:method "POST" :action (str "/admin/policies/" (:id p) "/delete")
                      :class "inline"}
               [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
               [:button {:type "submit"
                         :class "text-xs text-red-600 hover:text-red-800"
                         :onclick "return confirm('Delete this policy?')"}
                "Delete"]]]]])]]])))

(defn render-form
  "Create/edit policy form."
  [{:keys [policy editing? csrf-token user auth-enabled error]}]
  (layout/base-layout
    {:title (if editing? "Edit Policy" "New Policy")
     :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header (if editing? (str "Edit Policy: " (:name policy)) "New Policy"))

    (when error
      [:div {:class "rounded-lg border p-4 mb-6 bg-red-50 border-red-200 text-red-700"}
       error])

    [:form {:method "POST"
            :action (if editing?
                      (str "/admin/policies/" (:id policy) "/edit")
                      "/admin/policies")
            :class "bg-white rounded-lg shadow-sm border p-6 space-y-4"}
     [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]

     [:div
      [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Policy Name"]
      [:input {:type "text" :name "name" :value (or (:name policy) "")
               :required true
               :class "w-full border rounded px-3 py-2 text-sm"
               :placeholder "e.g., Production branch restriction"}]]

     [:div
      [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Description"]
      [:input {:type "text" :name "description" :value (or (:description policy) "")
               :class "w-full border rounded px-3 py-2 text-sm"
               :placeholder "Brief description of what this policy enforces"}]]

     [:div
      [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Policy Type"]
      [:select {:name "policy-type"
                :class "w-full border rounded px-3 py-2 text-sm bg-white"}
       (for [[val label desc] policy-types]
         [:option {:value val :selected (= val (:policy-type policy))}
          (str label " — " desc)])]]

     [:div
      [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Rules (JSON)"]
      [:textarea {:name "rules" :rows 6
                  :class "w-full border rounded px-3 py-2 text-sm font-mono"
                  :placeholder (get rule-placeholders "branch-restriction")}
       (if (:rules policy)
         (if (string? (:rules policy))
           (:rules policy)
           (try (json/write-str (:rules policy)) (catch Exception _ "")))
         "")]]

     [:div {:class "grid grid-cols-2 gap-4"}
      [:div
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Priority"]
       [:input {:type "number" :name "priority" :value (or (:priority policy) 100)
                :min 1 :max 1000
                :class "w-full border rounded px-3 py-2 text-sm"
                :placeholder "Lower = evaluated first"}]]
      [:div
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Enabled"]
       [:div {:class "flex items-center mt-2"}
        [:input {:type "checkbox" :name "enabled" :value "true"
                 :checked (if (nil? (:enabled policy)) true (:enabled policy))
                 :class "h-4 w-4 rounded border-gray-300"}]
        [:span {:class "ml-2 text-sm text-gray-600"} "Policy is active"]]]]

     [:div {:class "flex justify-end gap-3"}
      [:a {:href "/admin/policies"
           :class "px-4 py-2 border rounded text-sm font-medium text-gray-700 hover:bg-gray-50"}
       "Cancel"]
      [:button {:type "submit"
                :class "bg-blue-600 text-white px-4 py-2 rounded text-sm font-medium hover:bg-blue-700"}
       (if editing? "Update Policy" "Create Policy")]]]))

(defn render-evaluations
  "Policy evaluation history page."
  [{:keys [evaluations csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title "Policy Evaluations" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Policy Evaluation History")

    (if (empty? evaluations)
      [:div {:class "bg-white rounded-lg shadow-sm border p-8 text-center text-gray-500"}
       "No policy evaluations recorded yet."]
      [:div {:class "bg-white rounded-lg shadow-sm border overflow-hidden"}
       [:table {:class "w-full text-sm"}
        [:thead
         [:tr {:class "text-left text-gray-500 border-b bg-gray-50"}
          [:th {:class "py-3 px-4 font-medium"} "Build"]
          [:th {:class "py-3 px-4 font-medium"} "Stage"]
          [:th {:class "py-3 px-4 font-medium"} "Policy"]
          [:th {:class "py-3 px-4 font-medium"} "Result"]
          [:th {:class "py-3 px-4 font-medium"} "Reason"]
          [:th {:class "py-3 px-4 font-medium"} "Time"]]]
        [:tbody
         (for [e evaluations]
           [:tr {:class "border-b border-gray-100"}
            [:td {:class "py-2 px-4 font-mono text-xs"}
             [:a {:href (str "/builds/" (:build-id e))
                  :class "text-blue-600 hover:underline"}
              (subs (:build-id e) 0 (min 12 (count (:build-id e))))]]
            [:td {:class "py-2 px-4 text-gray-600"} (or (:stage-name e) "—")]
            [:td {:class "py-2 px-4 font-mono text-xs"} (subs (:policy-id e) 0 (min 12 (count (:policy-id e))))]
            [:td {:class "py-2 px-4"} (result-badge (:result e))]
            [:td {:class "py-2 px-4 text-gray-600 text-xs max-w-xs truncate"} (or (:reason e) "—")]
            [:td {:class "py-2 px-4 text-gray-500 text-xs"} (or (:evaluated-at e) "—")]])]]])))
