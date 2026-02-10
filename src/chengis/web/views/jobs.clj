(ns chengis.web.views.jobs
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [chengis.web.views.pipeline-viz :as pipeline-viz]
            [chengis.engine.dag :as dag])
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
           (c/trigger-button (:name job)
                            {:has-params? (seq (:parameters job))})]
          [:div {:class "flex items-center gap-4 mt-3 text-sm text-gray-500"}
           [:span (str (count (get-in job [:pipeline :stages])) " stages")]
           [:span {:class "text-gray-300"} "|"]
           [:span (str "Created: " (:created-at job))]]])])))

(defn render-detail
  "Job detail page: pipeline info + build history."
  [{:keys [job builds stats recent-history secret-names csrf-token]}]
  (let [pipeline (:pipeline job)]
    (layout/base-layout
      {:title (:name job) :csrf-token csrf-token}
      (c/page-header (:name job)
                     [:div {:class "flex items-center gap-3"}
                      [:a {:href (str "/jobs/" (URLEncoder/encode (str (:name job)) "UTF-8") "/pipeline")
                           :class "bg-indigo-600 text-white px-4 py-2 rounded-md text-sm font-medium
                                   hover:bg-indigo-700 active:bg-indigo-800 transition-colors
                                   focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2"}
                       "View Pipeline"]
                      (c/trigger-button (:name job)
                                        {:has-params? (seq (:parameters pipeline))})])

      ;; Pipeline description + source badge
      (when (or (:description pipeline) (get-in pipeline [:source :url]))
        [:div {:class "bg-white rounded-lg shadow-sm border mb-6 p-5"}
         (when (:description pipeline)
           [:p {:class "text-gray-600"} (:description pipeline)])
         (when (get-in pipeline [:source :url])
           [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium
                           bg-purple-100 text-purple-800 border border-purple-200 mt-2"}
            "Pipeline as Code"])])

      ;; Pipeline visualization graph (structural — no status colors)
      ;; Use DAG viz when pipeline has dependencies, otherwise linear graph
      (if (dag/has-dag? (:stages pipeline))
        (pipeline-viz/render-dag-pipeline (:stages pipeline))
        (c/pipeline-graph (:stages pipeline)))

      ;; Build stats + history chart
      (when (and stats (pos? (:total stats)))
        (list
          (c/build-stats-row stats)
          (c/build-history-chart recent-history)))

      ;; Secrets panel
      [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
       [:div {:class "px-5 py-4 border-b flex items-center justify-between"}
        [:h2 {:class "text-lg font-semibold text-gray-900"} "Secrets"]
        [:span {:class "text-xs text-gray-400"} "Values are never shown"]]
       [:div {:class "p-5"}
        (if (seq secret-names)
          [:div {:class "flex flex-wrap gap-2 mb-4"}
           (for [sn secret-names]
             [:span {:class "inline-flex items-center gap-1 px-3 py-1 rounded-full text-sm
                             bg-gray-100 text-gray-700 border"}
              [:span {:class "font-mono"} sn]
              [:form {:method "POST"
                      :action (str "/jobs/" (:name job) "/secrets/" sn)
                      :class "inline"
                      :onsubmit "return confirm('Delete secret?')"}
               [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
               [:button {:type "submit"
                         :class "text-red-400 hover:text-red-600 ml-1 text-xs"}
                "×"]]])]
          [:p {:class "text-gray-400 text-sm mb-4"} "No secrets configured."])
        ;; Add secret form
        [:form {:method "POST"
                :action (str "/jobs/" (:name job) "/secrets")
                :class "flex items-end gap-3"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
         [:div
          [:label {:class "block text-xs text-gray-500 mb-1"} "Name"]
          [:input {:type "text" :name "secret-name" :required true
                   :class "px-3 py-1.5 border rounded text-sm w-40"
                   :placeholder "MY_SECRET"}]]
         [:div
          [:label {:class "block text-xs text-gray-500 mb-1"} "Value"]
          [:input {:type "password" :name "secret-value" :required true
                   :class "px-3 py-1.5 border rounded text-sm w-48"
                   :placeholder "secret-value"}]]
         [:div
          [:label {:class "block text-xs text-gray-500 mb-1"} "Scope"]
          [:select {:name "scope"
                    :class "px-3 py-1.5 border rounded text-sm"}
           [:option {:value "global"} "Global"]
           [:option {:value "job"} "This Job"]]]
         [:button {:type "submit"
                   :class "px-4 py-1.5 bg-gray-800 text-white text-sm rounded hover:bg-gray-700"}
          "Add Secret"]]]]

      ;; Build history table
      [:div {:class "bg-white rounded-lg shadow-sm border"}
       [:div {:class "px-5 py-4 border-b"}
        [:h2 {:class "text-lg font-semibold text-gray-900"} "Build History"]]
       [:div {:class "p-0"}
        (c/build-table builds)]])))
