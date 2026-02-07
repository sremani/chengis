(ns chengis.web.views.jobs
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c])
  (:import [java.net URLEncoder]))

(defn render-list
  "Jobs listing page."
  [{:keys [jobs csrf-token]}]
  (layout/base-layout
    {:title "Jobs" :csrf-token csrf-token}
    (c/page-header "Jobs")
    (if (empty? jobs)
      [:div {:class "bg-white rounded-lg shadow-sm border p-12 text-center text-gray-400"}
       [:p {:class "text-lg"} "No jobs configured."]
       [:p {:class "text-sm mt-2"} "Create one with: chengis job create <pipeline-file>"]]
      [:div {:class "grid gap-4"}
       (for [job jobs]
         [:div {:class "bg-white rounded-lg shadow-sm border p-5 hover:shadow-md transition-shadow"}
          [:div {:class "flex items-center justify-between"}
           [:div
            [:a {:href (str "/jobs/" (URLEncoder/encode (str (:name job)) "UTF-8"))
                 :class "text-lg font-semibold text-blue-600 hover:text-blue-800 hover:underline"}
             (:name job)]
            (when-let [desc (get-in job [:pipeline :description])]
              [:p {:class "text-sm text-gray-500 mt-1"} desc])
            (when (get-in job [:pipeline :source :url])
              [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium
                              bg-purple-100 text-purple-700 mt-1"}
               "Pipeline as Code"])]
           (c/trigger-button (:name job))]
          [:div {:class "flex items-center gap-4 mt-3 text-sm text-gray-500"}
           [:span (str (count (get-in job [:pipeline :stages])) " stages")]
           [:span {:class "text-gray-300"} "|"]
           [:span (str "Created: " (:created-at job))]]])])))

(defn render-detail
  "Job detail page: pipeline info + build history."
  [{:keys [job builds csrf-token]}]
  (let [pipeline (:pipeline job)]
    (layout/base-layout
      {:title (:name job) :csrf-token csrf-token}
      (c/page-header (:name job) (c/trigger-button (:name job)))

      ;; Pipeline stages
      [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
       [:div {:class "px-5 py-4 border-b flex items-center justify-between"}
        [:h2 {:class "text-lg font-semibold text-gray-900"} "Pipeline"]
        (when (get-in pipeline [:source :url])
          [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium
                          bg-purple-100 text-purple-800 border border-purple-200"}
           "Pipeline as Code"])]
       [:div {:class "p-5"}
        (when (:description pipeline)
          [:p {:class "text-gray-600 mb-4"} (:description pipeline)])
        [:div {:class "flex items-center gap-2 flex-wrap"}
         (for [stage (:stages pipeline)]
           (list
             [:div {:class "bg-gray-50 border rounded-lg px-4 py-2 text-sm"}
              [:span {:class "font-medium"} (:stage-name stage)]
              [:span {:class "text-gray-400 ml-2"}
               (str "(" (count (:steps stage)) " step"
                    (when (> (count (:steps stage)) 1) "s") ")")
               (when (:parallel? stage)
                 [:span {:class "ml-1 text-blue-500"} " parallel"])]]
             [:span {:class "text-gray-300 text-lg"} "\u2192"]))]]]

      ;; Build history
      [:div {:class "bg-white rounded-lg shadow-sm border"}
       [:div {:class "px-5 py-4 border-b"}
        [:h2 {:class "text-lg font-semibold text-gray-900"} "Build History"]]
       [:div {:class "p-0"}
        (c/build-table builds)]])))
