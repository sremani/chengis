(ns chengis.web.views.components
  (:import [java.net URLEncoder]))

(defn status-badge
  "Colored badge for build/stage/step status."
  [status]
  (let [[bg-class label] (case status
                           :success  ["bg-green-100 text-green-800 border-green-200" "Success"]
                           :failure  ["bg-red-100 text-red-800 border-red-200" "Failure"]
                           :running  ["bg-blue-100 text-blue-800 border-blue-200 animate-pulse" "Running"]
                           :queued   ["bg-yellow-100 text-yellow-800 border-yellow-200" "Queued"]
                           :pending  ["bg-gray-100 text-gray-500 border-gray-200" "Pending"]
                           :skipped  ["bg-gray-100 text-gray-500 border-gray-200" "Skipped"]
                           :aborted  ["bg-orange-100 text-orange-800 border-orange-200" "Aborted"]
                           ["bg-gray-100 text-gray-600 border-gray-200" (name status)])]
    [:span {:class (str "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border " bg-class)}
     label]))

(defn stat-card
  "Dashboard stat card with number and label."
  [value label & [{:keys [color] :or {color "text-gray-900"}}]]
  [:div {:class "bg-white rounded-lg shadow-sm border p-5"}
   [:div {:class (str "text-3xl font-bold " color)} value]
   [:div {:class "text-gray-500 text-sm mt-1"} label]])

(defn trigger-button
  "Button that triggers a build via htmx POST."
  [job-name]
  [:button {:hx-post (str "/jobs/" (URLEncoder/encode (str job-name) "UTF-8") "/trigger")
            :hx-swap "none"
            :class "bg-blue-600 text-white px-4 py-2 rounded-md text-sm font-medium
                    hover:bg-blue-700 active:bg-blue-800 transition-colors
                    focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"}
   "Trigger Build"])

(def ^:private th-class
  "Standard Tailwind class for table header cells."
  "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider")

(defn build-table
  "Table of builds with status, number, trigger type, and timing."
  [builds & [{:keys [show-job?] :or {show-job? false}}]]
  (if (empty? builds)
    [:div {:class "text-center py-12 text-gray-400"}
     [:p {:class "text-lg"} "No builds yet."]]
    [:div {:class "overflow-hidden border rounded-lg"}
     [:table {:class "min-w-full divide-y divide-gray-200"}
      [:thead {:class "bg-gray-50"}
       [:tr
        [:th {:class th-class} "Status"]
        [:th {:class th-class} "Build"]
        (when show-job?
          [:th {:class th-class} "Job"])
        [:th {:class th-class} "Trigger"]
        [:th {:class th-class} "Branch"]
        [:th {:class th-class} "Source"]
        [:th {:class th-class} "Started"]
        [:th {:class th-class} "Completed"]]]
      [:tbody {:class "bg-white divide-y divide-gray-200"}
       (for [build builds]
         [:tr {:class "hover:bg-gray-50 transition-colors"}
          [:td {:class "px-4 py-3"} (status-badge (:status build))]
          [:td {:class "px-4 py-3 font-mono text-sm"}
           [:a {:href (str "/builds/" (:id build))
                :class "text-blue-600 hover:text-blue-800 hover:underline"}
            (str "#" (:build-number build))]]
          (when show-job?
            [:td {:class "px-4 py-3 text-sm text-gray-600"} (:job-id build)])
          [:td {:class "px-4 py-3 text-sm text-gray-600"} (or (:trigger-type build) "manual")]
          [:td {:class "px-4 py-3 text-sm"}
           (when (:git-branch build)
             [:span {:class "font-mono bg-blue-50 text-blue-700 px-2 py-0.5 rounded text-xs"}
              (:git-branch build)])]
          [:td {:class "px-4 py-3 text-sm"}
           (when (= "chengisfile" (:pipeline-source build))
             [:span {:class "font-mono bg-purple-50 text-purple-700 px-2 py-0.5 rounded text-xs"}
              "Chengisfile"])]
          [:td {:class "px-4 py-3 text-sm text-gray-500 font-mono"} (or (:started-at build) "-")]
          [:td {:class "px-4 py-3 text-sm text-gray-500 font-mono"} (or (:completed-at build) "-")]])]]]))

(defn page-header
  "Page title with optional right-side action."
  [title & [action]]
  [:div {:class "flex items-center justify-between mb-6"}
   [:h1 {:class "text-2xl font-bold text-gray-900"} title]
   (when action action)])

(defn card
  "Simple white card with optional title."
  [{:keys [title class] :or {class ""}} & body]
  [:div {:class (str "bg-white rounded-lg shadow-sm border " class)}
   (when title
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} title]])
   (into [:div {:class "p-5"}] body)])
