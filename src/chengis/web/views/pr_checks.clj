(ns chengis.web.views.pr-checks
  "Hiccup views for PR/MR status check management and results display."
  (:require [hiccup2.core :as h]))

;; ---------------------------------------------------------------------------
;; Status helpers
;; ---------------------------------------------------------------------------

(defn- status-badge
  "Render a status badge with appropriate color."
  [status]
  (let [colors (case (keyword status)
                 :success "bg-green-100 text-green-800"
                 :failure "bg-red-100 text-red-800"
                 :error   "bg-red-100 text-red-800"
                 :pending "bg-yellow-100 text-yellow-800"
                 :missing "bg-gray-100 text-gray-600"
                 "bg-gray-100 text-gray-600")]
    [:span {:class (str "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium " colors)}
     (name (or status :unknown))]))

(defn- check-icon
  "Render a check/cross icon for pass/fail."
  [passing?]
  (if passing?
    [:span {:class "text-green-500 mr-1"} "✓"]
    [:span {:class "text-red-500 mr-1"} "✗"]))

;; ---------------------------------------------------------------------------
;; Check configuration management
;; ---------------------------------------------------------------------------

(defn checks-config-panel
  "Render the check configuration panel for a job's settings."
  [job-id checks csrf-token]
  [:div {:class "bg-white rounded-lg shadow p-6"}
   [:h3 {:class "text-lg font-semibold mb-4"} "Required Status Checks"]
   [:p {:class "text-sm text-gray-500 mb-4"}
    "Configure which checks must pass before a PR can be merged."]

   ;; Existing checks table
   (if (empty? checks)
     [:p {:class "text-gray-400 text-sm italic"} "No checks configured."]
     [:table {:class "w-full text-sm mb-4"}
      [:thead
       [:tr {:class "border-b text-left text-gray-500"}
        [:th {:class "py-2 pr-4"} "Check Name"]
        [:th {:class "py-2 pr-4"} "Description"]
        [:th {:class "py-2 pr-4"} "Required"]
        [:th {:class "py-2"} ""]]]
      [:tbody
       (for [check checks]
         [:tr {:class "border-b hover:bg-gray-50"}
          [:td {:class "py-2 pr-4 font-mono text-sm"} (:check-name check)]
          [:td {:class "py-2 pr-4 text-gray-600"} (or (:description check) "—")]
          [:td {:class "py-2 pr-4"}
           (if (:required check)
             [:span {:class "text-green-600 font-medium"} "Yes"]
             [:span {:class "text-gray-400"} "No"])]
          [:td {:class "py-2"}
           [:form {:method "POST"
                   :action (str "/api/jobs/" job-id "/checks/" (:id check) "/delete")
                   :class "inline"}
            (when csrf-token
              [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
            [:button {:type "submit"
                      :class "text-red-500 hover:text-red-700 text-xs"}
             "Remove"]]]])]])

   ;; Add check form
   [:form {:method "POST"
           :action (str "/api/jobs/" job-id "/checks")
           :class "flex gap-3 items-end mt-4"}
    (when csrf-token
      [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
    [:div {:class "flex-1"}
     [:label {:class "block text-xs text-gray-500 mb-1"} "Check Name"]
     [:input {:type "text" :name "check-name" :required true
              :placeholder "e.g., chengis/build"
              :class "w-full border rounded px-3 py-1.5 text-sm"}]]
    [:div {:class "flex-1"}
     [:label {:class "block text-xs text-gray-500 mb-1"} "Description"]
     [:input {:type "text" :name "description"
              :placeholder "Optional description"
              :class "w-full border rounded px-3 py-1.5 text-sm"}]]
    [:div
     [:label {:class "block text-xs text-gray-500 mb-1"} "Required"]
     [:input {:type "checkbox" :name "required" :value "true" :checked true
              :class "mt-1"}]]
    [:button {:type "submit"
              :class "bg-blue-600 text-white px-4 py-1.5 rounded text-sm hover:bg-blue-700"}
     "Add Check"]]])

;; ---------------------------------------------------------------------------
;; Build check results display
;; ---------------------------------------------------------------------------

(defn build-check-results-panel
  "Render check results for a specific build."
  [build-id results merge-readiness]
  [:div {:class "bg-white rounded-lg shadow p-6"}
   [:div {:class "flex items-center justify-between mb-4"}
    [:h3 {:class "text-lg font-semibold"} "PR Status Checks"]
    (when merge-readiness
      (if (:ready? merge-readiness)
        [:span {:class "inline-flex items-center px-3 py-1 rounded-full text-sm font-medium bg-green-100 text-green-800"}
         "✓ Ready to merge"]
        [:span {:class "inline-flex items-center px-3 py-1 rounded-full text-sm font-medium bg-yellow-100 text-yellow-800"}
         (str (:passed merge-readiness) "/" (:total merge-readiness) " checks passed")]))]

   (if (empty? results)
     [:p {:class "text-gray-400 text-sm italic"} "No check results for this build."]
     [:div {:class "space-y-2"}
      (for [result results]
        [:div {:class "flex items-center justify-between p-3 rounded border hover:bg-gray-50"}
         [:div {:class "flex items-center"}
          (check-icon (= :success (:status result)))
          [:span {:class "font-mono text-sm mr-3"} (:check-name result)]
          (status-badge (:status result))]
         [:div {:class "text-xs text-gray-400"}
          (when (:completed-at result)
            (:completed-at result))]])])])

;; ---------------------------------------------------------------------------
;; Commit status summary
;; ---------------------------------------------------------------------------

(defn commit-status-summary
  "Render a compact commit status summary."
  [summary]
  [:div {:class "flex items-center gap-2"}
   (for [check (:checks summary)]
     [:div {:class "flex items-center text-xs" :title (:check-name check)}
      (check-icon (= :success (:status check)))
      [:span {:class "text-gray-600"} (:check-name check)]])])
