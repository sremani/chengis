(ns chengis.web.views.dependencies
  "Hiccup views for build dependency management."
  (:require [hiccup2.core :as h]))

;; ---------------------------------------------------------------------------
;; Dependency graph panel
;; ---------------------------------------------------------------------------

(defn dependencies-panel
  "Render the dependency configuration panel for a job."
  [{:keys [job-id dependencies dependents jobs csrf-token]}]
  [:div {:class "bg-white rounded-lg shadow p-6"}
   [:h3 {:class "text-lg font-semibold mb-4"} "Build Dependencies"]

   ;; Upstream dependencies
   [:div {:class "mb-6"}
    [:h4 {:class "text-sm font-medium text-gray-600 mb-2"} "Depends On (upstream)"]
    (if (empty? dependencies)
      [:p {:class "text-gray-400 text-sm italic"} "No upstream dependencies."]
      [:div {:class "space-y-2"}
       (for [dep dependencies]
         [:div {:class "flex items-center justify-between p-2 rounded border bg-gray-50"}
          [:div {:class "flex items-center gap-2"}
           [:span {:class "text-blue-600"} "\u2190"]
           [:span {:class "font-mono text-sm"} (:depends-on-job-id dep)]
           [:span {:class "text-xs text-gray-400"}
            (str "on: " (or (:trigger-on dep) "success"))]]
          [:form {:method "POST"
                  :action (str "/api/dependencies/" (:id dep) "/delete")
                  :class "inline"}
           (when csrf-token
             [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
           [:button {:type "submit"
                     :class "text-red-500 hover:text-red-700 text-xs"}
            "Remove"]]])])]

   ;; Downstream dependents
   [:div {:class "mb-6"}
    [:h4 {:class "text-sm font-medium text-gray-600 mb-2"} "Triggers (downstream)"]
    (if (empty? dependents)
      [:p {:class "text-gray-400 text-sm italic"} "No downstream dependents."]
      [:div {:class "space-y-2"}
       (for [dep dependents]
         [:div {:class "flex items-center gap-2 p-2 rounded border bg-gray-50"}
          [:span {:class "text-green-600"} "\u2192"]
          [:span {:class "font-mono text-sm"} (:job-id dep)]
          [:span {:class "text-xs text-gray-400"}
           (str "on: " (or (:trigger-on dep) "success"))]])])]

   ;; Add dependency form
   [:form {:method "POST"
           :action (str "/api/jobs/" job-id "/dependencies")
           :class "flex gap-3 items-end"}
    (when csrf-token
      [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
    [:div {:class "flex-1"}
     [:label {:class "block text-xs text-gray-500 mb-1"} "Upstream Job"]
     [:select {:name "upstream-job-id" :required true
               :class "w-full border rounded px-3 py-1.5 text-sm"}
      [:option {:value ""} "Select job..."]
      (for [job (remove #(= (:id %) job-id) jobs)]
        [:option {:value (:id job)} (:pipeline-name job)])]]
    [:div
     [:label {:class "block text-xs text-gray-500 mb-1"} "Trigger On"]
     [:select {:name "trigger-on"
               :class "border rounded px-3 py-1.5 text-sm"}
      [:option {:value "success"} "Success"]
      [:option {:value "failure"} "Failure"]
      [:option {:value "any"} "Any (success or failure)"]]]
    [:button {:type "submit"
              :class "bg-blue-600 text-white px-4 py-1.5 rounded text-sm hover:bg-blue-700"}
     "Add Dependency"]]])
