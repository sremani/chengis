(ns chengis.web.views.iac
  "Infrastructure-as-Code dashboard views — project cards, plan history, state overview."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- tool-badge
  "Color-coded badge for IaC tool type."
  [tool-type]
  (let [colors (case (str tool-type)
                 "terraform" "bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-200"
                 "pulumi" "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200"
                 "cloudformation" "bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200"
                 "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200")]
    [:span {:class (str "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium " colors)}
     (escape-html (str tool-type))]))

(defn- status-badge
  "Color-coded badge for plan/execution status."
  [status]
  (let [colors (case (str status)
                 "pending" "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"
                 "succeeded" "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"
                 "failed" "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"
                 "awaiting-approval" "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
                 "approved" "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200"
                 "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200")]
    [:span {:class (str "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium " colors)}
     (escape-html (str status))]))

(defn- plan-action-badge
  "Badge for plan action type (plan/apply/destroy/preview)."
  [action]
  (let [colors (case (str action)
                 "plan" "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200"
                 "apply" "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"
                 "destroy" "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"
                 "preview" "bg-indigo-100 text-indigo-800 dark:bg-indigo-900 dark:text-indigo-200"
                 "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200")]
    [:span {:class (str "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium " colors)}
     (escape-html (str action))]))

(defn- project-card
  "Card showing an IaC project with tool badge, latest plan info, and action links."
  [project latest-plan csrf-token]
  [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 border-l-4"
         :style (str "border-left-color: "
                     (case (str (:tool-type project))
                       "terraform" "#A855F7"
                       "pulumi" "#3B82F6"
                       "cloudformation" "#F97316"
                       "#6B7280"))}
   [:div {:class "flex justify-between items-start mb-3"}
    [:div
     [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-white"}
      (escape-html (str (:job-id project)))]
     [:p {:class "text-xs text-gray-500 dark:text-gray-400 font-mono"}
      (escape-html (str (:id project)))]]
    [:div {:class "flex gap-2"}
     (tool-badge (:tool-type project))
     (when (:auto-detect project)
       [:span {:class "inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-teal-100 text-teal-800 dark:bg-teal-900 dark:text-teal-200"}
        "Auto-detect"])]]
   (when (:working-dir project)
     [:p {:class "text-sm text-gray-600 dark:text-gray-300 mb-2"}
      "Working dir: "
      [:span {:class "font-mono text-xs"} (escape-html (str (:working-dir project)))]])
   ;; Latest plan summary
   (if latest-plan
     [:div {:class "mt-3 p-3 bg-gray-50 dark:bg-gray-700/50 rounded"}
      [:div {:class "flex items-center gap-2 mb-1"}
       [:span {:class "text-xs text-gray-500 dark:text-gray-400"} "Latest plan:"]
       (status-badge (:status latest-plan))
       (plan-action-badge (:action latest-plan))]
      (when (or (:resources-add latest-plan)
                (:resources-change latest-plan)
                (:resources-destroy latest-plan))
        [:div {:class "flex gap-3 mt-1 text-xs"}
         (when (pos? (or (:resources-add latest-plan) 0))
           [:span {:class "text-green-600 dark:text-green-400 font-medium"}
            (str "+" (:resources-add latest-plan))])
         (when (pos? (or (:resources-change latest-plan) 0))
           [:span {:class "text-yellow-600 dark:text-yellow-400 font-medium"}
            (str "~" (:resources-change latest-plan))])
         (when (pos? (or (:resources-destroy latest-plan) 0))
           [:span {:class "text-red-600 dark:text-red-400 font-medium"}
            (str "-" (:resources-destroy latest-plan))])])
      [:p {:class "text-xs text-gray-400 dark:text-gray-500 mt-1"}
       (escape-html (str (:created-at latest-plan)))]]
     [:p {:class "text-xs text-gray-400 dark:text-gray-500 mt-3 italic"} "No plans yet"])
   ;; Action links
   [:div {:class "flex items-center gap-3 mt-4"}
    [:a {:href (str "/iac/projects/" (:id project))
         :class "text-sm text-blue-600 hover:text-blue-800 dark:text-blue-400 hover:underline"}
     "Details"]
    [:form {:method "POST" :action (str "/iac/projects/" (:id project) "/execute")
            :class "inline"}
     [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
     [:input {:type "hidden" :name "action" :value "plan"}]
     [:button {:type "submit"
               :class "text-sm px-3 py-1 bg-blue-600 text-white rounded hover:bg-blue-700"}
      "Run Plan"]]]])

(defn- plan-row
  "Table row showing a single plan entry."
  [plan]
  [:tr {:class "border-b border-gray-100 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-800"}
   [:td {:class "py-3 px-4 text-sm"}
    [:a {:href (str "/iac/plans/" (:id plan))
         :class "text-blue-600 hover:underline dark:text-blue-400 font-mono"}
     (escape-html (subs (str (:id plan)) 0 (min 8 (count (str (:id plan))))))]]
   [:td {:class "py-3 px-4"} (plan-action-badge (:action plan))]
   [:td {:class "py-3 px-4"} (status-badge (:status plan))]
   [:td {:class "py-3 px-4 text-sm"}
    [:div {:class "flex gap-2"}
     (when (pos? (or (:resources-add plan) 0))
       [:span {:class "text-green-600 dark:text-green-400 font-medium"}
        (str "+" (:resources-add plan))])
     (when (pos? (or (:resources-change plan) 0))
       [:span {:class "text-yellow-600 dark:text-yellow-400 font-medium"}
        (str "~" (:resources-change plan))])
     (when (pos? (or (:resources-destroy plan) 0))
       [:span {:class "text-red-600 dark:text-red-400 font-medium"}
        (str "-" (:resources-destroy plan))])
     (when (and (not (pos? (or (:resources-add plan) 0)))
                (not (pos? (or (:resources-change plan) 0)))
                (not (pos? (or (:resources-destroy plan) 0))))
       [:span {:class "text-gray-400 dark:text-gray-500"} "no changes"])]]
   [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
    (when (:duration-ms plan)
      (str (quot (:duration-ms plan) 1000) "s"))]
   [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
    (escape-html (str (:initiated-by plan)))]
   [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
    (escape-html (str (:created-at plan)))]])

(defn render-dashboard
  "Render the IaC dashboard with project cards, stats, and create form."
  [{:keys [projects latest-plans plan-stats csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title "Infrastructure as Code" :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       [:div {:class "flex justify-between items-center mb-6"}
        [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"} "Infrastructure as Code"]
        [:div {:class "flex gap-3"}
         [:a {:href "/iac/plans" :class "text-sm text-blue-600 hover:underline dark:text-blue-400"} "Plans"]
         [:a {:href "/iac/states" :class "text-sm text-blue-600 hover:underline dark:text-blue-400"} "States"]]]
       ;; Stats summary
       (when plan-stats
         [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 mb-6"}
          (for [[label value color] [["Succeeded" (get plan-stats "succeeded" 0) "text-green-600"]
                                     ["Failed" (get plan-stats "failed" 0) "text-red-600"]
                                     ["Awaiting Approval" (get plan-stats "awaiting-approval" 0) "text-yellow-600"]
                                     ["Pending" (get plan-stats "pending" 0) "text-gray-600"]]]
            [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-4 text-center"}
             [:p {:class (str "text-2xl font-bold " color)} value]
             [:p {:class "text-xs text-gray-500 dark:text-gray-400 mt-1"} label]])])
       ;; Project cards
       (if (seq projects)
         [:div {:class "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 mb-8"}
          (for [project projects]
            (project-card project (get latest-plans (:id project)) csrf-token))]
         [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-8 text-center mb-8"}
          [:p {:class "text-gray-500 dark:text-gray-400"} "No IaC projects yet. Create one below."]])
       ;; Create project form
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6"}
        [:h2 {:class "text-lg font-semibold mb-4 text-gray-900 dark:text-white"} "Create IaC Project"]
        [:form {:method "POST" :action "/iac/projects"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
         [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-4"}
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Job ID"]
           [:input {:type "text" :name "job_id" :required true
                    :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"
                    :placeholder "my-infra-project"}]]
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Tool Type"]
           [:select {:name "tool_type" :required true
                     :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}
            [:option {:value "terraform"} "Terraform"]
            [:option {:value "pulumi"} "Pulumi"]
            [:option {:value "cloudformation"} "CloudFormation"]]]
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Working Directory"]
           [:input {:type "text" :name "working_dir"
                    :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"
                    :placeholder "./infra"}]]]
         [:button {:type "submit"
                   :class "mt-4 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 text-sm font-medium"}
          "Create Project"]]]]))))

(defn render-project-detail
  "Render a single IaC project detail page with plans, states, and execute form."
  [{:keys [project plans states cost-estimates csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title (str "IaC: " (:job-id project)) :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       ;; Header
       [:div {:class "flex justify-between items-center mb-6"}
        [:div
         [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"}
          (escape-html (str (:job-id project)))]
         [:p {:class "text-sm text-gray-500 dark:text-gray-400 font-mono"}
          (escape-html (str (:id project)))]]
        [:div {:class "flex gap-2 items-center"}
         (tool-badge (:tool-type project))
         (when (:working-dir project)
           [:span {:class "text-sm text-gray-500 dark:text-gray-400 font-mono"}
            (escape-html (str (:working-dir project)))])]]
       ;; Config JSON
       (when (:config-json project)
         [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
          [:h2 {:class "text-lg font-semibold mb-3 text-gray-900 dark:text-white"} "Configuration"]
          [:pre {:class "bg-gray-50 dark:bg-gray-900 rounded p-4 text-sm font-mono text-gray-700 dark:text-gray-300 overflow-x-auto max-h-64 overflow-y-auto"}
           (escape-html (str (:config-json project)))]])
       ;; Execute form
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
        [:h2 {:class "text-lg font-semibold mb-3 text-gray-900 dark:text-white"} "Execute"]
        [:form {:method "POST" :action (str "/iac/projects/" (:id project) "/execute")
                :class "flex items-end gap-4"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
         [:div
          [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1"} "Action"]
          [:select {:name "action" :required true
                    :class "block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}
           [:option {:value "plan"} "Plan"]
           [:option {:value "apply"} "Apply"]
           [:option {:value "destroy"} "Destroy"]]]
         [:button {:type "submit"
                   :class "px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 text-sm font-medium"}
          "Execute"]]]
       ;; Plans history
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow overflow-hidden mb-6"}
        [:div {:class "px-6 py-4 border-b border-gray-200 dark:border-gray-700"}
         [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-white"} "Plans History"]]
        [:table {:class "min-w-full divide-y divide-gray-200 dark:divide-gray-700"}
         [:thead {:class "bg-gray-50 dark:bg-gray-900"}
          [:tr
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "ID"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Action"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Status"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Resources"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Duration"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Initiated By"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Created"]]]
         [:tbody
          (if (seq plans)
            (for [plan plans] (plan-row plan))
            [:tr [:td {:colspan "7" :class "py-8 text-center text-gray-500 dark:text-gray-400"} "No plans yet"]])]]]
       ;; Recent states
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
        [:div {:class "flex justify-between items-center mb-4"}
         [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-white"} "Recent States"]
         (when (:id project)
           [:a {:href (str "/iac/projects/" (:id project) "/states")
                :class "text-sm text-blue-600 hover:underline dark:text-blue-400"}
            "View All"])]
        (if (seq states)
          [:div {:class "space-y-2"}
           (for [s states]
             [:div {:class "flex justify-between items-center py-2 px-3 border-b border-gray-100 dark:border-gray-700"}
              [:div {:class "flex items-center gap-3"}
               [:span {:class "text-sm font-medium text-gray-900 dark:text-white"}
                (str "v" (:version s))]
               [:span {:class "text-xs font-mono text-gray-500 dark:text-gray-400"}
                (escape-html (subs (str (:state-hash s)) 0 (min 12 (count (str (:state-hash s))))))]
               (when (:workspace-name s)
                 [:span {:class "text-xs px-1.5 py-0.5 rounded bg-indigo-100 text-indigo-700 dark:bg-indigo-900 dark:text-indigo-200"}
                  (escape-html (str (:workspace-name s)))])]
              [:div {:class "flex items-center gap-3"}
               [:span {:class "text-xs text-gray-400 dark:text-gray-500"}
                (escape-html (str (:created-at s)))]
               [:span {:class "text-xs text-gray-400 dark:text-gray-500"}
                (escape-html (str (:created-by s)))]]])]
          [:p {:class "text-sm text-gray-500 dark:text-gray-400"} "No states recorded"])]
       ;; Cost estimates summary
       (when (seq cost-estimates)
         [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
          [:h2 {:class "text-lg font-semibold mb-3 text-gray-900 dark:text-white"} "Recent Cost Estimates"]
          [:div {:class "space-y-2"}
           (for [ce cost-estimates]
             [:div {:class "flex justify-between items-center py-2 px-3 border-b border-gray-100 dark:border-gray-700"}
              [:div {:class "flex items-center gap-3"}
               [:span {:class "text-sm font-mono text-blue-600 dark:text-blue-400"}
                (escape-html (subs (str (:plan-id ce)) 0 (min 8 (count (str (:plan-id ce))))))]
               [:span {:class "text-sm font-medium text-gray-900 dark:text-white"}
                (str "$" (:total-monthly ce) "/mo")]]
              [:span {:class "text-xs text-gray-400 dark:text-gray-500"}
               (escape-html (str (:created-at ce)))]])]])
       ;; Delete project
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 border border-red-200 dark:border-red-800"}
        [:h2 {:class "text-lg font-semibold mb-3 text-red-600 dark:text-red-400"} "Danger Zone"]
        [:p {:class "text-sm text-gray-600 dark:text-gray-300 mb-4"}
         "Deleting this project will remove all plans, states, and cost estimates. This action cannot be undone."]
        [:form {:method "POST" :action (str "/iac/projects/" (:id project) "/delete")}
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
         [:button {:type "submit"
                   :class "px-4 py-2 bg-red-600 text-white rounded-md hover:bg-red-700 text-sm font-medium"
                   :onclick "return confirm('Are you sure you want to delete this IaC project?');"}
          "Delete Project"]]]]))))

(defn render-plan-detail
  "Render a single IaC plan detail page with resource diff and approval controls."
  [{:keys [plan cost-estimate csrf-token user]}]
  (let [plan-json (:plan-json plan)
        resources (when plan-json (:resources plan-json))]
    (str (h/html
      (layout/base-layout
        {:title (str "Plan " (subs (str (:id plan)) 0 (min 8 (count (str (:id plan))))))
         :csrf-token csrf-token :user user}
        [:div {:class "container mx-auto px-4 py-8"}
         ;; Header
         [:div {:class "flex justify-between items-center mb-6"}
          [:div
           [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"} "Plan Details"]
           [:p {:class "text-sm text-gray-500 dark:text-gray-400 font-mono"}
            (escape-html (str (:id plan)))]]
          [:div {:class "flex gap-2 items-center"}
           (status-badge (:status plan))
           (plan-action-badge (:action plan))]]
         ;; Info grid
         [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
          [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 text-sm"}
           [:div
            [:span {:class "text-gray-500 dark:text-gray-400 block"} "Project"]
            [:span {:class "font-medium font-mono"}
             [:a {:href (str "/iac/projects/" (:project-id plan))
                  :class "text-blue-600 hover:underline dark:text-blue-400"}
              (escape-html (subs (str (:project-id plan)) 0 (min 8 (count (str (:project-id plan))))))]]]
           [:div
            [:span {:class "text-gray-500 dark:text-gray-400 block"} "Initiated By"]
            [:span {:class "font-medium"} (escape-html (str (:initiated-by plan)))]]
           [:div
            [:span {:class "text-gray-500 dark:text-gray-400 block"} "Created"]
            [:span {:class "font-medium"} (escape-html (str (:created-at plan)))]]
           (when (:duration-ms plan)
             [:div
              [:span {:class "text-gray-500 dark:text-gray-400 block"} "Duration"]
              [:span {:class "font-medium"} (str (quot (:duration-ms plan) 1000) "s")]])]]
         ;; Resource summary
         [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
          [:h2 {:class "text-lg font-semibold mb-3 text-gray-900 dark:text-white"} "Resource Summary"]
          [:div {:class "flex gap-6 text-lg"}
           [:span {:class "text-green-600 dark:text-green-400 font-bold"}
            (str "+" (or (:resources-add plan) 0) " to add")]
           [:span {:class "text-yellow-600 dark:text-yellow-400 font-bold"}
            (str "~" (or (:resources-change plan) 0) " to change")]
           [:span {:class "text-red-600 dark:text-red-400 font-bold"}
            (str "-" (or (:resources-destroy plan) 0) " to destroy")]]]
         ;; Resource diff table
         (when (seq resources)
           [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow overflow-hidden mb-6"}
            [:div {:class "px-6 py-4 border-b border-gray-200 dark:border-gray-700"}
             [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-white"} "Resource Changes"]]
            [:table {:class "min-w-full divide-y divide-gray-200 dark:divide-gray-700"}
             [:thead {:class "bg-gray-50 dark:bg-gray-900"}
              [:tr
               [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Action"]
               [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Resource Type"]
               [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Name"]]]
             [:tbody
              (for [r resources]
                (let [action-str (str (:action r))
                      row-color (case action-str
                                  "create" "bg-green-50 dark:bg-green-900/10"
                                  "update" "bg-yellow-50 dark:bg-yellow-900/10"
                                  "delete" "bg-red-50 dark:bg-red-900/10"
                                  "")]
                  [:tr {:class (str "border-b border-gray-100 dark:border-gray-700 " row-color)}
                   [:td {:class "py-3 px-4"}
                    (let [badge-colors (case action-str
                                         "create" "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"
                                         "update" "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
                                         "delete" "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"
                                         "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200")]
                      [:span {:class (str "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium " badge-colors)}
                       (escape-html action-str)])]
                   [:td {:class "py-3 px-4 text-sm font-mono text-gray-700 dark:text-gray-300"}
                    (escape-html (str (:resource-type r)))]
                   [:td {:class "py-3 px-4 text-sm font-mono text-gray-700 dark:text-gray-300"}
                    (escape-html (str (:name r)))]]))
              ;; Summary row
              [:tr {:class "bg-gray-50 dark:bg-gray-900 font-medium"}
               [:td {:class "py-3 px-4 text-sm" :colspan "3"}
                [:span {:class "text-gray-600 dark:text-gray-300"}
                 (str (count resources) " resource(s) affected")]]]]]])
         ;; Cost estimate
         (when cost-estimate
           [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
            [:h2 {:class "text-lg font-semibold mb-3 text-gray-900 dark:text-white"} "Cost Estimate"]
            [:div {:class "grid grid-cols-2 gap-4 mb-4"}
             [:div {:class "bg-blue-50 dark:bg-blue-900/20 rounded p-4 text-center"}
              [:p {:class "text-2xl font-bold text-blue-600 dark:text-blue-400"}
               (str "$" (:total-monthly cost-estimate))]
              [:p {:class "text-xs text-gray-500 dark:text-gray-400 mt-1"} "Monthly"]]
             [:div {:class "bg-blue-50 dark:bg-blue-900/20 rounded p-4 text-center"}
              [:p {:class "text-2xl font-bold text-blue-600 dark:text-blue-400"}
               (str "$" (:total-hourly cost-estimate))]
              [:p {:class "text-xs text-gray-500 dark:text-gray-400 mt-1"} "Hourly"]]]
            (when-let [resources-cost (:resources cost-estimate)]
              [:table {:class "min-w-full divide-y divide-gray-200 dark:divide-gray-700"}
               [:thead {:class "bg-gray-50 dark:bg-gray-900"}
                [:tr
                 [:th {:class "py-2 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Resource"]
                 [:th {:class "py-2 px-4 text-right text-xs font-medium text-gray-500 uppercase"} "Monthly"]
                 [:th {:class "py-2 px-4 text-right text-xs font-medium text-gray-500 uppercase"} "Hourly"]]]
               [:tbody
                (for [rc resources-cost]
                  [:tr {:class "border-b border-gray-100 dark:border-gray-700"}
                   [:td {:class "py-2 px-4 text-sm font-mono"} (escape-html (str (:name rc)))]
                   [:td {:class "py-2 px-4 text-sm text-right"} (str "$" (:monthly rc))]
                   [:td {:class "py-2 px-4 text-sm text-right"}
                    (str "$" (format "%.4f" (/ (or (:monthly rc) 0.0) 730.0)))]])]])])
         ;; Approval buttons
         (when (= "awaiting-approval" (str (:status plan)))
           [:div {:class "bg-yellow-50 dark:bg-yellow-900/20 rounded-lg shadow p-6 mb-6"}
            [:h2 {:class "text-lg font-semibold mb-3 text-yellow-800 dark:text-yellow-200"} "Approval Required"]
            [:p {:class "text-sm text-yellow-700 dark:text-yellow-300 mb-4"}
             "This plan requires approval before it can be applied."]
            [:div {:class "flex gap-3"}
             [:form {:method "POST" :action (str "/iac/plans/" (:id plan) "/approve")}
              [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
              [:button {:type "submit"
                        :class "px-4 py-2 bg-green-600 text-white rounded-md hover:bg-green-700 text-sm font-medium"}
               "Approve"]]
             [:form {:method "POST" :action (str "/iac/plans/" (:id plan) "/reject")}
              [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
              [:button {:type "submit"
                        :class "px-4 py-2 bg-red-600 text-white rounded-md hover:bg-red-700 text-sm font-medium"}
               "Reject"]]]])
         ;; Output
         (when (:output plan)
           [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
            [:h2 {:class "text-lg font-semibold mb-3 text-gray-900 dark:text-white"} "Output"]
            [:pre {:class "bg-gray-50 dark:bg-gray-900 rounded p-4 text-sm font-mono text-gray-700 dark:text-gray-300 overflow-x-auto max-h-96 overflow-y-auto whitespace-pre-wrap"}
             (escape-html (str (:output plan)))]])
         ;; Error output
         (when (:error-output plan)
           [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6 border border-red-200 dark:border-red-800"}
            [:h2 {:class "text-lg font-semibold mb-3 text-red-600 dark:text-red-400"} "Error Output"]
            [:pre {:class "bg-red-50 dark:bg-red-900/20 rounded p-4 text-sm font-mono text-red-700 dark:text-red-300 overflow-x-auto max-h-96 overflow-y-auto whitespace-pre-wrap"}
             (escape-html (str (:error-output plan)))]])])))))
