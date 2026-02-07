(ns chengis.web.views.components
  (:require [clojure.string :as str])
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
  "Button that triggers a build. If the job has parameters, loads a form first.
   Otherwise triggers directly via htmx POST."
  ([job-name] (trigger-button job-name nil))
  ([job-name {:keys [has-params?]}]
   (let [encoded (URLEncoder/encode (str job-name) "UTF-8")]
     (if has-params?
       ;; Parameter form trigger: GET the form, inject it below
       [:div {:id "trigger-container"}
        [:button {:hx-get (str "/jobs/" encoded "/trigger-form")
                  :hx-target "#trigger-container"
                  :hx-swap "beforeend"
                  :class "bg-blue-600 text-white px-4 py-2 rounded-md text-sm font-medium
                          hover:bg-blue-700 active:bg-blue-800 transition-colors
                          focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"}
         "Trigger Build ▼"]]
       ;; Direct trigger (no params)
       [:button {:hx-post (str "/jobs/" encoded "/trigger")
                 :hx-swap "none"
                 :class "bg-blue-600 text-white px-4 py-2 rounded-md text-sm font-medium
                         hover:bg-blue-700 active:bg-blue-800 transition-colors
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"}
        "Trigger Build"]))))

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

;; --- Pipeline Visualization ---

(defn- stage-status-classes
  "Return border + bg classes for a pipeline stage node based on status."
  [status]
  (case status
    :success  "border-green-400 bg-green-50"
    :failure  "border-red-400 bg-red-50"
    :running  "border-blue-400 bg-blue-50 animate-pulse"
    :aborted  "border-orange-400 bg-orange-50"
    :skipped  "border-gray-300 bg-gray-50"
    :queued   "border-yellow-400 bg-yellow-50"
    ;; Default (no status / pending / structural view)
    "border-gray-300 bg-white"))

(defn- step-status-dot
  "Tiny colored dot for step status inside a pipeline node."
  [status]
  (let [color (case status
                :success "bg-green-500"
                :failure "bg-red-500"
                :running "bg-blue-500 animate-pulse"
                :aborted "bg-orange-500"
                :skipped "bg-gray-400"
                "bg-gray-300")]
    [:span {:class (str "inline-block w-2 h-2 rounded-full " color)}]))

(defn- pipeline-stage-node
  "Render a single stage node in the pipeline graph.
   stage-def: {:stage-name ... :steps [...] :parallel? ...}
   stage-status: optional map {:status :success} or nil
   step-statuses: optional seq of {:step-name ... :status ...} or nil"
  [stage-def stage-status step-statuses]
  (let [status (:status stage-status)
        border-classes (stage-status-classes status)
        post? (str/starts-with? (or (:stage-name stage-def) "") "post:")]
    [:div {:class (str "flex-shrink-0 w-44 rounded-lg border-2 p-3 shadow-sm " border-classes)
           :id (str "pipeline-stage-" (:stage-name stage-def))}
     ;; Stage name + badges
     [:div {:class "flex items-center justify-between mb-2"}
      [:span {:class "font-semibold text-sm text-gray-900 truncate"}
       (:stage-name stage-def)]
      [:div {:class "flex items-center gap-1"}
       (when (:parallel? stage-def)
         [:span {:class "text-[10px] bg-blue-100 text-blue-700 px-1 rounded"} "P"])
       (when post?
         [:span {:class "text-[10px] bg-purple-100 text-purple-700 px-1 rounded"} "post"])]]
     ;; Steps list
     [:div {:class "space-y-1"}
      (let [steps (or (:steps stage-def) [])
            status-map (when step-statuses
                         (into {} (map (juxt :step-name :status) step-statuses)))]
        (for [step steps]
          [:div {:class "flex items-center gap-1.5 text-xs text-gray-600"}
           (if status-map
             (step-status-dot (get status-map (:step-name step)))
             [:span {:class "inline-block w-2 h-2 rounded-full bg-gray-300"}])
           [:span {:class "truncate"} (:step-name step)]]))]
     ;; Status badge at bottom
     (when status
       [:div {:class "mt-2 pt-2 border-t border-gray-200"}
        (status-badge status)])]))

(defn pipeline-graph
  "Visual pipeline graph showing stages as connected nodes.
   Arguments:
     stages       - seq of stage definitions [{:stage-name ... :steps [...] :parallel? ...}]
     stage-results - optional: seq of {:stage-name ... :status ...} from build results
     step-results  - optional: seq of {:stage-name ... :step-name ... :status ...}"
  [stages & [{:keys [stage-results step-results]}]]
  (let [status-by-stage (when stage-results
                          (into {} (map (juxt :stage-name identity) stage-results)))
        steps-by-stage (when step-results
                         (group-by :stage-name step-results))]
    [:div {:class "bg-white rounded-lg shadow-sm border mb-6" :id "pipeline-viz"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Pipeline"]]
     [:div {:class "p-5 overflow-x-auto"}
      [:div {:class "flex items-center gap-3 min-w-max"}
       (interpose
         ;; Arrow connector between stages
         [:div {:class "flex items-center text-gray-400 text-lg font-bold select-none"} "\u2192"]
         (for [stage stages]
           (pipeline-stage-node
             stage
             (get status-by-stage (:stage-name stage))
             (get steps-by-stage (:stage-name stage)))))]]]))

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

;; --- Build History Chart ---

(defn- bar-color-class
  "Tailwind background class for a build status bar."
  [status]
  (case status
    :success "bg-green-500"
    :failure "bg-red-500"
    :aborted "bg-orange-500"
    :running "bg-blue-500"
    "bg-gray-400"))

(defn build-history-chart
  "CSS-only bar chart of recent builds. Each bar is colored by status, clickable."
  [builds]
  (when (seq builds)
    (let [builds-asc (reverse builds)]
      [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
       [:div {:class "px-5 py-4 border-b"}
        [:h2 {:class "text-lg font-semibold text-gray-900"} "Build History"]]
       [:div {:class "p-5"}
        [:div {:class "flex items-end gap-1 h-24"}
         (for [build builds-asc]
           (let [status (:status build)
                 color (bar-color-class status)]
             [:a {:href (str "/builds/" (:id build))
                  :class (str "flex-1 min-w-[8px] max-w-[24px] rounded-t transition-all hover:opacity-80 relative group "
                              color)
                  :style "height: 70%"
                  :title (str "#" (:build-number build) " - " (name status))}
              ;; Tooltip on hover (CSS-only via group-hover)
              [:span {:class "absolute bottom-full left-1/2 -translate-x-1/2 mb-1 px-2 py-1 text-xs
                              bg-gray-900 text-white rounded whitespace-nowrap opacity-0 group-hover:opacity-100
                              pointer-events-none transition-opacity z-10"}
               (str "#" (:build-number build) " " (name status))]]))]
        ;; Legend
        [:div {:class "flex items-center gap-4 mt-3 text-xs text-gray-500"}
         [:div {:class "flex items-center gap-1"}
          [:span {:class "w-3 h-3 rounded-sm bg-green-500"}] "Success"]
         [:div {:class "flex items-center gap-1"}
          [:span {:class "w-3 h-3 rounded-sm bg-red-500"}] "Failure"]
         [:div {:class "flex items-center gap-1"}
          [:span {:class "w-3 h-3 rounded-sm bg-orange-500"}] "Aborted"]]]])))

(defn build-stats-row
  "Row of stat cards showing success rate, totals, etc."
  [stats]
  (let [{:keys [total success failure aborted success-rate]} stats
        rate-pct (int (* 100 (or success-rate 0)))
        rate-color (cond
                     (>= rate-pct 80) "text-green-600"
                     (>= rate-pct 50) "text-yellow-600"
                     :else "text-red-600")]
    [:div {:class "grid grid-cols-2 md:grid-cols-5 gap-4 mb-6"}
     [:div {:class "bg-white rounded-lg shadow-sm border p-4"}
      [:div {:class (str "text-2xl font-bold " rate-color)} (str rate-pct "%")]
      [:div {:class "text-gray-500 text-sm"} "Success Rate"]]
     [:div {:class "bg-white rounded-lg shadow-sm border p-4"}
      [:div {:class "text-2xl font-bold text-gray-900"} total]
      [:div {:class "text-gray-500 text-sm"} "Total Builds"]]
     [:div {:class "bg-white rounded-lg shadow-sm border p-4"}
      [:div {:class "text-2xl font-bold text-green-600"} success]
      [:div {:class "text-gray-500 text-sm"} "Successful"]]
     [:div {:class "bg-white rounded-lg shadow-sm border p-4"}
      [:div {:class "text-2xl font-bold text-red-600"} failure]
      [:div {:class "text-gray-500 text-sm"} "Failed"]]
     [:div {:class "bg-white rounded-lg shadow-sm border p-4"}
      [:div {:class "text-2xl font-bold text-orange-600"} aborted]
      [:div {:class "text-gray-500 text-sm"} "Aborted"]]]))
