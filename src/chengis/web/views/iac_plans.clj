(ns chengis.web.views.iac-plans
  "IaC plan visualization — resource diff tables, cost estimates, approval forms."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- resource-action-color
  "Return Tailwind CSS classes for a resource action type."
  [action]
  (case (str action)
    "create" "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"
    "Add" "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"
    "update" "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
    "Modify" "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
    "delete" "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"
    "Remove" "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"
    "read" "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"
    "no-op" "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"
    "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"))

(defn- resource-action-row-color
  "Return Tailwind CSS row background for a resource action type."
  [action]
  (case (str action)
    "create" "bg-green-50 dark:bg-green-900/10"
    "Add" "bg-green-50 dark:bg-green-900/10"
    "update" "bg-yellow-50 dark:bg-yellow-900/10"
    "Modify" "bg-yellow-50 dark:bg-yellow-900/10"
    "delete" "bg-red-50 dark:bg-red-900/10"
    "Remove" "bg-red-50 dark:bg-red-900/10"
    ""))

(defn- count-by-action
  "Count resources matching a set of action names."
  [resources action-set]
  (count (filter #(action-set (str (:action %))) resources)))

(defn render-plan-diff
  "Render a resource diff table for a plan.
   Expects {:keys [resources]} where each resource has :action, :resource-type, :name."
  [{:keys [resources]}]
  (if (seq resources)
    (let [add-count (count-by-action resources #{"create" "Add"})
          change-count (count-by-action resources #{"update" "Modify"})
          delete-count (count-by-action resources #{"delete" "Remove"})
          other-count (count-by-action resources #{"read" "no-op"})]
      [:div {:class "overflow-hidden"}
       [:table {:class "min-w-full divide-y divide-gray-200 dark:divide-gray-700"}
        [:thead {:class "bg-gray-50 dark:bg-gray-900"}
         [:tr
          [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Action"]
          [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Resource Type"]
          [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Name"]]]
        [:tbody
         (for [r resources]
           [:tr {:class (str "border-b border-gray-100 dark:border-gray-700 "
                             (resource-action-row-color (:action r)))}
            [:td {:class "py-3 px-4"}
             [:span {:class (str "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium "
                                 (resource-action-color (:action r)))}
              (escape-html (str (:action r)))]]
            [:td {:class "py-3 px-4 text-sm font-mono text-gray-700 dark:text-gray-300"}
             (escape-html (str (:resource-type r)))]
            [:td {:class "py-3 px-4 text-sm font-mono text-gray-700 dark:text-gray-300"}
             (escape-html (str (:name r)))]])
         ;; Summary row
         [:tr {:class "bg-gray-50 dark:bg-gray-900 font-medium"}
          [:td {:class "py-3 px-4 text-sm" :colspan "3"}
           [:div {:class "flex gap-4"}
            [:span {:class "text-gray-600 dark:text-gray-300"}
             (str (count resources) " resource(s)")]
            (when (pos? add-count)
              [:span {:class "text-green-600 dark:text-green-400 font-medium"}
               (str "+" add-count " to add")])
            (when (pos? change-count)
              [:span {:class "text-yellow-600 dark:text-yellow-400 font-medium"}
               (str "~" change-count " to change")])
            (when (pos? delete-count)
              [:span {:class "text-red-600 dark:text-red-400 font-medium"}
               (str "-" delete-count " to destroy")])
            (when (pos? other-count)
              [:span {:class "text-gray-500 dark:text-gray-400"}
               (str other-count " unchanged")])]]]]]])
    [:p {:class "text-sm text-gray-500 dark:text-gray-400 py-4"} "No resource changes in this plan."]))

(defn render-cost-estimate
  "Render a cost estimate breakdown section.
   Expects {:keys [cost-estimate]} with :monthly-cost, :hourly-cost, :currency, :resources."
  [{:keys [cost-estimate]}]
  (if cost-estimate
    (let [currency (or (:currency cost-estimate) "USD")]
      [:div {:class "space-y-4"}
       ;; Totals
       [:div {:class "grid grid-cols-2 gap-4"}
        [:div {:class "bg-blue-50 dark:bg-blue-900/20 rounded-lg p-4 text-center"}
         [:p {:class "text-2xl font-bold text-blue-600 dark:text-blue-400"}
          (str "$" (:total-monthly cost-estimate))]
         [:p {:class "text-xs text-gray-500 dark:text-gray-400 mt-1"}
          (str "Monthly (" currency ")")]]
        [:div {:class "bg-blue-50 dark:bg-blue-900/20 rounded-lg p-4 text-center"}
         [:p {:class "text-2xl font-bold text-blue-600 dark:text-blue-400"}
          (str "$" (:total-hourly cost-estimate))]
         [:p {:class "text-xs text-gray-500 dark:text-gray-400 mt-1"}
          (str "Hourly (" currency ")")]]]
       ;; Per-resource breakdown
       (when-let [resources-cost (:resources cost-estimate)]
         [:div {:class "overflow-hidden"}
          [:table {:class "min-w-full divide-y divide-gray-200 dark:divide-gray-700"}
           [:thead {:class "bg-gray-50 dark:bg-gray-900"}
            [:tr
             [:th {:class "py-2 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Resource"]
             [:th {:class "py-2 px-4 text-right text-xs font-medium text-gray-500 uppercase"} (str "Monthly (" currency ")")]
             [:th {:class "py-2 px-4 text-right text-xs font-medium text-gray-500 uppercase"} (str "Hourly (" currency ")")]]]
           [:tbody
            (for [rc resources-cost]
              [:tr {:class "border-b border-gray-100 dark:border-gray-700"}
               [:td {:class "py-2 px-4 text-sm font-mono text-gray-700 dark:text-gray-300"}
                (escape-html (str (:name rc)))]
               [:td {:class "py-2 px-4 text-sm text-right text-gray-700 dark:text-gray-300"}
                (str "$" (:monthly rc))]
               [:td {:class "py-2 px-4 text-sm text-right text-gray-700 dark:text-gray-300"}
                (str "$" (format "%.4f" (/ (or (:monthly rc) 0.0) 730.0)))]])]]])])
    [:p {:class "text-sm text-gray-500 dark:text-gray-400"} "No cost estimate available."]))

(defn render-approval-form
  "Render approve/reject buttons for a plan awaiting approval.
   Expects {:keys [plan-id csrf-token]}."
  [{:keys [plan-id csrf-token]}]
  [:div {:class "flex gap-3"}
   [:form {:method "POST" :action (str "/iac/plans/" plan-id "/approve")}
    [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
    [:button {:type "submit"
              :class "px-4 py-2 bg-green-600 text-white rounded-md hover:bg-green-700 text-sm font-medium"}
     "Approve"]]
   [:form {:method "POST" :action (str "/iac/plans/" plan-id "/reject")}
    [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
    [:button {:type "submit"
              :class "px-4 py-2 bg-red-600 text-white rounded-md hover:bg-red-700 text-sm font-medium"}
     "Reject"]]])

(defn render-states-page
  "Render the states list page for an IaC project.
   Expects {:keys [states project csrf-token user]}."
  [{:keys [states project csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title (str "States: " (:job-id project)) :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       ;; Header
       [:div {:class "flex justify-between items-center mb-6"}
        [:div
         [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"}
          "IaC States"]
         [:p {:class "text-sm text-gray-500 dark:text-gray-400"}
          "Project: "
          [:a {:href (str "/iac/projects/" (:id project))
               :class "text-blue-600 hover:underline dark:text-blue-400"}
           (escape-html (str (:job-id project)))]]]
        [:span {:class "text-sm text-gray-500 dark:text-gray-400"}
         (str (count states) " state(s)")]]
       ;; States table
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow overflow-hidden"}
        [:table {:class "min-w-full divide-y divide-gray-200 dark:divide-gray-700"}
         [:thead {:class "bg-gray-50 dark:bg-gray-900"}
          [:tr
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Version"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Hash"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Size"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Workspace"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Created By"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Created"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Actions"]]]
         [:tbody
          (if (seq states)
            (for [s states]
              [:tr {:class "border-b border-gray-100 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-800"}
               [:td {:class "py-3 px-4 text-sm font-medium text-gray-900 dark:text-white"}
                [:a {:href (str "/iac/projects/" (:id project) "/states/" (:id s))
                     :class "text-blue-600 hover:underline dark:text-blue-400"}
                 (str "v" (:version s))]]
               [:td {:class "py-3 px-4 text-sm font-mono text-gray-500 dark:text-gray-400"}
                (escape-html (subs (str (:state-hash s)) 0 (min 12 (count (str (:state-hash s))))))]
               [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
                (when (:state-size s)
                  (let [kb (quot (:state-size s) 1024)]
                    (if (pos? kb) (str kb " KB") (str (:state-size s) " B"))))]
               [:td {:class "py-3 px-4 text-sm"}
                (if (:workspace-name s)
                  [:span {:class "px-1.5 py-0.5 rounded text-xs bg-indigo-100 text-indigo-700 dark:bg-indigo-900 dark:text-indigo-200"}
                   (escape-html (str (:workspace-name s)))]
                  [:span {:class "text-gray-400 dark:text-gray-500 text-xs"} "default"])]
               [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
                (escape-html (str (:created-by s)))]
               [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
                (escape-html (str (:created-at s)))]
               [:td {:class "py-3 px-4 text-sm"}
                (when (:locked s)
                  [:form {:method "POST" :action (str "/iac/projects/" (:id project) "/states/" (:id s) "/force-unlock")
                          :class "inline"}
                   [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
                   [:button {:type "submit"
                             :class "text-xs px-2 py-1 bg-red-600 text-white rounded hover:bg-red-700"
                             :onclick "return confirm('Are you sure you want to force unlock this state?');"}
                    "Force Unlock"]])]])
            [:tr [:td {:colspan "7" :class "py-8 text-center text-gray-500 dark:text-gray-400"} "No states recorded"]])]]]]))))

(defn render-state-detail
  "Render a single state detail page with lock info and state data preview.
   Expects {:keys [state lock csrf-token user]}."
  [{:keys [state lock csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title (str "State v" (:version state)) :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       ;; Header
       [:div {:class "flex justify-between items-center mb-6"}
        [:div
         [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"}
          (str "State v" (:version state))]
         [:p {:class "text-sm text-gray-500 dark:text-gray-400 font-mono"}
          (escape-html (str (:id state)))]]
        [:div {:class "flex gap-2 items-center"}
         (if lock
           [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"}
            "Locked"]
           [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"}
            "Unlocked"])]]
       ;; Info grid
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
        [:h2 {:class "text-lg font-semibold mb-3 text-gray-900 dark:text-white"} "State Information"]
        [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 text-sm"}
         [:div
          [:span {:class "text-gray-500 dark:text-gray-400 block"} "Version"]
          [:span {:class "font-medium"} (str "v" (:version state))]]
         [:div
          [:span {:class "text-gray-500 dark:text-gray-400 block"} "Hash"]
          [:span {:class "font-medium font-mono"}
           (escape-html (str (:state-hash state)))]]
         [:div
          [:span {:class "text-gray-500 dark:text-gray-400 block"} "Size"]
          [:span {:class "font-medium"}
           (if (:state-size state)
             (let [kb (quot (:state-size state) 1024)]
               (if (pos? kb) (str kb " KB") (str (:state-size state) " B")))
             "N/A")]]
         [:div
          [:span {:class "text-gray-500 dark:text-gray-400 block"} "Workspace"]
          [:span {:class "font-medium"}
           (if (:workspace-name state)
             (escape-html (str (:workspace-name state)))
             "default")]]
         [:div
          [:span {:class "text-gray-500 dark:text-gray-400 block"} "Created By"]
          [:span {:class "font-medium"} (escape-html (str (:created-by state)))]]
         [:div
          [:span {:class "text-gray-500 dark:text-gray-400 block"} "Created At"]
          [:span {:class "font-medium"} (escape-html (str (:created-at state)))]]
         (when (:project-id state)
           [:div
            [:span {:class "text-gray-500 dark:text-gray-400 block"} "Project"]
            [:span {:class "font-medium font-mono"}
             [:a {:href (str "/iac/projects/" (:project-id state))
                  :class "text-blue-600 hover:underline dark:text-blue-400"}
              (escape-html (subs (str (:project-id state)) 0 (min 8 (count (str (:project-id state))))))]]])]]
       ;; Lock information
       (when lock
         [:div {:class "bg-red-50 dark:bg-red-900/20 rounded-lg shadow p-6 mb-6 border border-red-200 dark:border-red-800"}
          [:h2 {:class "text-lg font-semibold mb-3 text-red-800 dark:text-red-200"} "Lock Information"]
          [:div {:class "grid grid-cols-2 gap-4 text-sm mb-4"}
           [:div
            [:span {:class "text-red-600 dark:text-red-400 block"} "Locked By"]
            [:span {:class "font-medium text-red-800 dark:text-red-200"}
             (escape-html (str (:locked-by lock)))]]
           (when (:lock-reason lock)
             [:div
              [:span {:class "text-red-600 dark:text-red-400 block"} "Reason"]
              [:span {:class "font-medium text-red-800 dark:text-red-200"}
               (escape-html (str (:lock-reason lock)))]])
           (when (:locked-at lock)
             [:div
              [:span {:class "text-red-600 dark:text-red-400 block"} "Locked At"]
              [:span {:class "font-medium text-red-800 dark:text-red-200"}
               (escape-html (str (:locked-at lock)))]])
           (when (:expires-at lock)
             [:div
              [:span {:class "text-red-600 dark:text-red-400 block"} "Expires At"]
              [:span {:class "font-medium text-red-800 dark:text-red-200"}
               (escape-html (str (:expires-at lock)))]])]
          [:form {:method "POST" :action (str "/iac/projects/" (:project-id state) "/states/" (:id state) "/force-unlock")}
           [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
           [:button {:type "submit"
                     :class "px-4 py-2 bg-red-600 text-white rounded-md hover:bg-red-700 text-sm font-medium"
                     :onclick "return confirm('Are you sure you want to force unlock this state? This may cause conflicts.');"}
            "Force Unlock"]]])
       ;; State data preview
       (when (:state-data state)
         [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6"}
          [:h2 {:class "text-lg font-semibold mb-3 text-gray-900 dark:text-white"} "State Data Preview"]
          [:p {:class "text-xs text-gray-500 dark:text-gray-400 mb-2"}
           "Showing first 1000 characters of decompressed state data."]
          [:pre {:class "bg-gray-50 dark:bg-gray-900 rounded p-4 text-sm font-mono text-gray-700 dark:text-gray-300 overflow-x-auto max-h-96 overflow-y-auto whitespace-pre-wrap"}
           (let [data-str (str (:state-data state))]
             (escape-html (subs data-str 0 (min 1000 (count data-str)))))]])]))))
