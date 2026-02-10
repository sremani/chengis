(ns chengis.web.views.cron
  "Hiccup views for cron schedule management."
  (:require [hiccup2.core :as h]))

;; ---------------------------------------------------------------------------
;; Schedule list
;; ---------------------------------------------------------------------------

(defn schedules-panel
  "Render the cron schedules panel for admin/developer view."
  [{:keys [schedules jobs csrf-token]}]
  [:div {:class "bg-white rounded-lg shadow p-6"}
   [:div {:class "flex items-center justify-between mb-4"}
    [:h3 {:class "text-lg font-semibold"} "Cron Schedules"]
    [:span {:class "text-xs text-gray-400"} (str (count schedules) " schedules")]]

   (if (empty? schedules)
     [:p {:class "text-gray-400 text-sm italic"} "No cron schedules configured."]
     [:table {:class "w-full text-sm"}
      [:thead
       [:tr {:class "border-b text-left text-gray-500"}
        [:th {:class "py-2 pr-4"} "Job"]
        [:th {:class "py-2 pr-4"} "Cron Expression"]
        [:th {:class "py-2 pr-4"} "Next Run"]
        [:th {:class "py-2 pr-4"} "Status"]
        [:th {:class "py-2"} ""]]]
      [:tbody
       (for [sched schedules]
         [:tr {:class "border-b hover:bg-gray-50"}
          [:td {:class "py-2 pr-4"} (:job-id sched)]
          [:td {:class "py-2 pr-4 font-mono text-xs"} (:cron-expression sched)]
          [:td {:class "py-2 pr-4 text-xs text-gray-500"} (or (:next-run-at sched) "—")]
          [:td {:class "py-2 pr-4"}
           (if (:enabled sched)
             [:span {:class "text-green-600 text-xs font-medium"} "Active"]
             [:span {:class "text-gray-400 text-xs"} "Disabled"])]
          [:td {:class "py-2"}
           [:form {:method "POST"
                   :action (str "/api/cron/" (:id sched) "/delete")
                   :class "inline"}
            (when csrf-token
              [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
            [:button {:type "submit"
                      :class "text-red-500 hover:text-red-700 text-xs"}
             "Remove"]]]])]])

   ;; Add schedule form
   [:form {:method "POST"
           :action "/api/cron"
           :class "flex gap-3 items-end mt-4"}
    (when csrf-token
      [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
    [:div {:class "flex-1"}
     [:label {:class "block text-xs text-gray-500 mb-1"} "Job"]
     [:select {:name "job-id" :required true
               :class "w-full border rounded px-3 py-1.5 text-sm"}
      [:option {:value ""} "Select job..."]
      (for [job jobs]
        [:option {:value (:id job)} (:pipeline-name job)])]]
    [:div {:class "flex-1"}
     [:label {:class "block text-xs text-gray-500 mb-1"} "Cron Expression"]
     [:input {:type "text" :name "cron-expression" :required true
              :placeholder "0 * * * *"
              :class "w-full border rounded px-3 py-1.5 text-sm font-mono"}]]
    [:div {:class "flex-1"}
     [:label {:class "block text-xs text-gray-500 mb-1"} "Timezone"]
     [:input {:type "text" :name "timezone" :value "UTC"
              :class "w-full border rounded px-3 py-1.5 text-sm"}]]
    [:button {:type "submit"
              :class "bg-blue-600 text-white px-4 py-1.5 rounded text-sm hover:bg-blue-700"}
     "Add Schedule"]]])

;; ---------------------------------------------------------------------------
;; Recent cron runs
;; ---------------------------------------------------------------------------

(defn cron-runs-panel
  "Render recent cron run history."
  [{:keys [runs]}]
  [:div {:class "bg-white rounded-lg shadow p-6 mt-6"}
   [:h3 {:class "text-lg font-semibold mb-4"} "Recent Cron Runs"]
   (if (empty? runs)
     [:p {:class "text-gray-400 text-sm italic"} "No cron runs yet."]
     [:table {:class "w-full text-sm"}
      [:thead
       [:tr {:class "border-b text-left text-gray-500"}
        [:th {:class "py-2 pr-4"} "Schedule"]
        [:th {:class "py-2 pr-4"} "Status"]
        [:th {:class "py-2 pr-4"} "Triggered At"]
        [:th {:class "py-2"} "Build ID"]]]
      [:tbody
       (for [run runs]
         [:tr {:class "border-b hover:bg-gray-50"}
          [:td {:class "py-2 pr-4 text-xs"} (:schedule-id run)]
          [:td {:class "py-2 pr-4"}
           (let [colors (case (:status run)
                          "triggered" "bg-green-100 text-green-800"
                          "missed" "bg-yellow-100 text-yellow-800"
                          "failed" "bg-red-100 text-red-800"
                          "bg-gray-100 text-gray-600")]
             [:span {:class (str "inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium " colors)}
              (:status run)])]
          [:td {:class "py-2 pr-4 text-xs text-gray-500"} (:triggered-at run)]
          [:td {:class "py-2 text-xs"}
           (when (:build-id run)
             [:a {:href (str "/builds/" (:build-id run))
                  :class "text-blue-600 hover:underline"}
              (subs (:build-id run) 0 (min 8 (count (:build-id run))))])]])]])])
