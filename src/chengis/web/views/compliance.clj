(ns chengis.web.views.compliance
  "Compliance reporting UI: templates, report runs, hash chain status."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [clojure.data.json :as json]))

(defn- status-badge [status]
  (let [[bg text] (case status
                    "completed" ["bg-green-100 text-green-800" "Completed"]
                    "running"   ["bg-blue-100 text-blue-800" "Running"]
                    "failed"    ["bg-red-100 text-red-800" "Failed"]
                    "pending"   ["bg-yellow-100 text-yellow-800" "Pending"]
                    ["bg-gray-100 text-gray-800" (or status "Unknown")])]
    [:span {:class (str "px-2 py-0.5 rounded-full text-xs font-medium " bg)} text]))

(defn- chain-badge [valid]
  (cond
    (true? valid)
    [:span {:class "px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800"}
     "Chain Valid"]
    (false? valid)
    [:span {:class "px-2 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800"}
     "Chain Broken"]
    :else
    [:span {:class "px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-800"}
     "Not Verified"]))

(defn render
  "Compliance page: templates, recent runs, hash chain status."
  [{:keys [templates runs chain-status csrf-token user auth-enabled message error]}]
  (layout/base-layout
    {:title "Compliance" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Compliance Reports")

    (when message
      [:div {:class "rounded-lg border p-4 mb-6 bg-green-50 border-green-200 text-green-700"}
       message])
    (when error
      [:div {:class "rounded-lg border p-4 mb-6 bg-red-50 border-red-200 text-red-700"}
       error])

    ;; Hash chain verification
    [:div {:class "bg-white rounded-lg shadow-sm border p-5 mb-6"}
     [:div {:class "flex items-center justify-between mb-4"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Audit Hash Chain"]
      [:form {:method "POST" :action "/admin/compliance/verify-chain"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:button {:type "submit"
                 :class "bg-blue-600 text-white px-3 py-1.5 rounded text-sm font-medium hover:bg-blue-700 transition-colors"}
        "Verify Chain"]]]
     (when chain-status
       [:div {:class "flex items-center gap-4 text-sm"}
        (chain-badge (:valid chain-status))
        [:span {:class "text-gray-500"}
         (str (:entries-checked chain-status) " entries checked")]
        (when (:first-invalid-id chain-status)
          [:span {:class "text-red-600 font-mono text-xs"}
           (str "First invalid: " (:first-invalid-id chain-status))])])]

    ;; Report templates
    [:div {:class "bg-white rounded-lg shadow-sm border p-5 mb-6"}
     [:h2 {:class "text-lg font-semibold text-gray-900 mb-4"} "Report Templates"]
     (if (empty? templates)
       [:p {:class "text-gray-500 text-sm"} "No report templates configured."]
       [:div {:class "space-y-3"}
        (for [tmpl templates]
          [:div {:class "flex items-center justify-between border-b border-gray-100 pb-3"}
           [:div
            [:div {:class "font-medium text-gray-900"} (:title tmpl)]
            [:div {:class "text-xs text-gray-500"} (:report-type tmpl)]
            (when (:description tmpl)
              [:div {:class "text-sm text-gray-600 mt-1"} (:description tmpl)])]
           [:form {:method "POST" :action "/admin/compliance/generate" :class "flex items-center gap-2"}
            [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
            [:input {:type "hidden" :name "report-id" :value (:id tmpl)}]
            [:input {:type "date" :name "period-start"
                     :class "border rounded px-2 py-1 text-sm"
                     :placeholder "Start date"}]
            [:input {:type "date" :name "period-end"
                     :class "border rounded px-2 py-1 text-sm"
                     :placeholder "End date"}]
            [:button {:type "submit"
                      :class "bg-green-600 text-white px-3 py-1.5 rounded text-sm font-medium hover:bg-green-700 transition-colors"}
             "Generate"]]])])]

    ;; Recent runs
    [:div {:class "bg-white rounded-lg shadow-sm border p-5"}
     [:h2 {:class "text-lg font-semibold text-gray-900 mb-4"} "Recent Report Runs"]
     (if (empty? runs)
       [:p {:class "text-gray-500 text-sm"} "No reports generated yet."]
       [:table {:class "w-full text-sm"}
        [:thead
         [:tr {:class "text-left text-gray-500 border-b"}
          [:th {:class "py-2 font-medium"} "Run ID"]
          [:th {:class "py-2 font-medium"} "Status"]
          [:th {:class "py-2 font-medium"} "Period"]
          [:th {:class "py-2 font-medium"} "Generated"]
          [:th {:class "py-2 font-medium"} "Actions"]]]
        [:tbody
         (for [run runs]
           [:tr {:class "border-b border-gray-100"}
            [:td {:class "py-2 font-mono text-xs"}
             [:a {:href (str "/admin/compliance/runs/" (:id run))
                  :class "text-blue-600 hover:underline"}
              (subs (:id run) 0 (min 12 (count (:id run))))]]
            [:td {:class "py-2"} (status-badge (:status run))]
            [:td {:class "py-2 text-gray-600"}
             (str (or (:period-start run) "—") " to " (or (:period-end run) "—"))]
            [:td {:class "py-2 text-gray-600"} (or (:generated-by run) "—")]
            [:td {:class "py-2"}
             (when (= "completed" (:status run))
               [:a {:href (str "/admin/compliance/runs/" (:id run) "/export")
                    :class "text-blue-600 hover:underline text-xs"}
                "Export JSON"])]])]])]))

(defn render-run-detail
  "Detail view for a compliance report run."
  [{:keys [run report-template csrf-token user auth-enabled]}]
  (let [summary (when (:summary run)
                  (try (json/read-str (:summary run) :key-fn keyword) (catch Exception _ nil)))]
    (layout/base-layout
      {:title "Report Run" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
      (c/page-header (str "Report: " (or (:title report-template) (:report-id run))))

      [:div {:class "bg-white rounded-lg shadow-sm border p-5 mb-6"}
       [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 text-sm mb-4"}
        [:div
         [:span {:class "text-gray-500 block"} "Status"]
         (status-badge (:status run))]
        [:div
         [:span {:class "text-gray-500 block"} "Period"]
         [:span (str (or (:period-start run) "—") " to " (or (:period-end run) "—"))]]
        [:div
         [:span {:class "text-gray-500 block"} "Generated By"]
         [:span (or (:generated-by run) "—")]]
        [:div
         [:span {:class "text-gray-500 block"} "Report Hash"]
         (when (:report-hash run)
           [:code {:class "text-xs" :title (:report-hash run)}
            (subs (:report-hash run) 0 (min 16 (count (:report-hash run))))])]]

       (when summary
         [:div {:class "mt-4 border-t pt-4"}
          [:h3 {:class "text-sm font-semibold mb-2"} "Report Summary"]
          (for [section (:sections summary)]
            [:div {:class "mb-4 bg-gray-50 rounded p-3"}
             [:h4 {:class "font-medium text-gray-900 mb-2"} (or (:section section) "Summary")]
             (when-let [events (:events section)]
               [:div {:class "text-sm text-gray-600 space-y-1"}
                [:div (str "Total events: " (:total-events events))]
                [:div (str "Unique users: " (:unique-users events))]
                (when (seq (:by-action events))
                  [:div {:class "mt-1"}
                   (for [[action cnt] (sort-by val > (:by-action events))]
                     [:span {:class "inline-block bg-gray-200 rounded px-2 py-0.5 text-xs mr-1 mb-1"}
                      (str (name action) ": " cnt)])])])
             (when (contains? section :chain-valid)
               [:div {:class "mt-1"}
                (chain-badge (:chain-valid section))
                [:span {:class "text-xs text-gray-500 ml-2"}
                 (str (:entries-checked section) " entries")]])
             (when (seq (:findings section))
               [:div {:class "mt-2"}
                (for [finding (:findings section)]
                  [:div {:class "text-xs text-orange-700 bg-orange-50 rounded px-2 py-1 mb-1"}
                   finding])])])])])))
