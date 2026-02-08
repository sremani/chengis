(ns chengis.web.views.approvals
  "Approval gates list page — shows pending approvals for builds."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [hiccup.util :refer [escape-html]]))

(defn- status-badge
  "Colored badge for approval status."
  [status]
  (let [colors (case status
                 "pending" "bg-yellow-100 text-yellow-800"
                 "approved" "bg-green-100 text-green-800"
                 "rejected" "bg-red-100 text-red-800"
                 "timed_out" "bg-gray-100 text-gray-800"
                 "bg-gray-100 text-gray-800")]
    [:span {:class (str "px-2 py-0.5 text-xs font-medium rounded " colors)}
     status]))

(defn- gate-row
  "Single approval gate row."
  [gate csrf-token]
  [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
   [:td {:class "py-3 px-4 text-sm"}
    [:a {:href (str "/builds/" (:build-id gate))
         :class "text-blue-600 hover:underline font-mono text-xs"}
     (subs (:build-id gate) 0 (min 12 (count (:build-id gate))))]]
   [:td {:class "py-3 px-4 text-sm font-medium"}
    (escape-html (or (:stage-name gate) "—"))]
   [:td {:class "py-3 px-4 text-sm text-gray-600"}
    (escape-html (or (:message gate) "—"))]
   [:td {:class "py-3 px-4 text-sm"}
    [:span {:class "px-2 py-0.5 text-xs font-medium rounded bg-blue-100 text-blue-800"}
     (:required-role gate)]]
   [:td {:class "py-3 px-4"}
    (status-badge (:status gate))]
   [:td {:class "py-3 px-4 text-xs text-gray-400"}
    (:created-at gate)]
   [:td {:class "py-3 px-4"}
    (when (= "pending" (:status gate))
      [:div {:class "flex gap-2"}
       [:form {:method "POST" :action (str "/approvals/" (:id gate) "/approve")}
        (when csrf-token
          [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
        [:button {:type "submit"
                  :class "px-3 py-1 text-xs bg-green-600 text-white rounded hover:bg-green-700"}
         "Approve"]]
       [:form {:method "POST" :action (str "/approvals/" (:id gate) "/reject")}
        (when csrf-token
          [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
        [:button {:type "submit"
                  :class "px-3 py-1 text-xs bg-red-600 text-white rounded hover:bg-red-700"}
         "Reject"]]])]])

(defn render
  "Render the approvals page."
  [{:keys [gates pending-count csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title "Approvals" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Approval Gates")

    [:div {:class "bg-white rounded-lg shadow-sm border overflow-hidden"}
     [:div {:class "px-5 py-3 border-b bg-gray-50 flex items-center justify-between"}
      [:span {:class "text-sm text-gray-600"}
       (str (or pending-count 0) " pending approval(s)")]
      [:span {:class "text-sm text-gray-500"}
       "Approve or reject pipeline stage gates"]]

     (if (empty? gates)
       [:div {:class "p-8 text-center text-gray-500"}
        "No approval gates found."]
       [:table {:class "w-full text-sm"}
        [:thead
         [:tr {:class "text-left text-gray-500 border-b bg-gray-50"}
          [:th {:class "py-2 px-4 font-medium"} "Build"]
          [:th {:class "py-2 px-4 font-medium"} "Stage"]
          [:th {:class "py-2 px-4 font-medium"} "Message"]
          [:th {:class "py-2 px-4 font-medium"} "Role"]
          [:th {:class "py-2 px-4 font-medium"} "Status"]
          [:th {:class "py-2 px-4 font-medium"} "Created"]
          [:th {:class "py-2 px-4 font-medium"} "Actions"]]]
        [:tbody
         (for [gate gates]
           (gate-row gate csrf-token))]])]))
