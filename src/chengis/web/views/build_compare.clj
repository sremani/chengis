(ns chengis.web.views.build-compare
  "Build comparison views — selection form and side-by-side diff results."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [clojure.string :as str]))

(defn- format-duration
  "Format a duration in seconds to a human-readable string."
  [seconds]
  (when seconds
    (cond
      (< seconds 60)   (format "%.1fs" (double seconds))
      (< seconds 3600) (format "%dm %ds" (quot seconds 60) (rem seconds 60))
      :else            (format "%dh %dm" (quot seconds 3600) (rem (quot seconds 60) 60)))))

(defn- delta-badge
  "Render a +/- delta badge for duration changes. Green for negative (faster),
   red for positive (slower)."
  [delta-s]
  (when delta-s
    (let [positive? (pos? delta-s)
          color (if positive? "text-red-600 bg-red-50" "text-green-600 bg-green-50")
          sign (if positive? "+" "")]
      [:span {:class (str "inline-flex items-center px-2 py-0.5 rounded text-xs font-mono font-medium " color)}
       (str sign (format-duration delta-s))])))

(defn- status-match-class
  "Return row background class based on whether statuses match."
  [status-a status-b]
  (cond
    (and (nil? status-a) (some? status-b)) "bg-blue-50 dark:bg-blue-900/30"
    (and (some? status-a) (nil? status-b)) "bg-orange-50 dark:bg-orange-900/30"
    (= status-a status-b) ""
    :else "bg-yellow-50 dark:bg-yellow-900/30"))

(defn- status-or-dash
  "Render status badge or dash placeholder."
  [status]
  (if status
    (c/status-badge status)
    [:span {:class "text-gray-300"} "-"]))

(defn render-compare-select
  "Selection form for choosing two builds to compare."
  [{:keys [builds-list job-name build-a-id build-b-id]}]
  [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 mb-6"}
   [:div {:class "px-5 py-4 border-b"}
    [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100"} "Select Builds to Compare"]]
   [:form {:method "get" :action "/compare" :class "p-5"}
    [:div {:class "grid grid-cols-1 md:grid-cols-4 gap-4 items-end"}
     [:div
      [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1"} "Job (optional)"]
      [:input {:type "text" :name "job" :value (or job-name "")
               :placeholder "Filter by job name"
               :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"}]]
     [:div
      [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1"} "Build A"]
      [:select {:name "a" :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500"}
       [:option {:value ""} "Select build..."]
       (for [b builds-list]
         [:option {:value (:id b)
                   :selected (= (str (:id b)) (str build-a-id))}
          (str "#" (:build-number b) " - " (name (or (:status b) :unknown))
               (when (:job-id b) (str " (" (:job-id b) ")")))])]]
     [:div
      [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1"} "Build B"]
      [:select {:name "b" :class "w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500"}
       [:option {:value ""} "Select build..."]
       (for [b builds-list]
         [:option {:value (:id b)
                   :selected (= (str (:id b)) (str build-b-id))}
          (str "#" (:build-number b) " - " (name (or (:status b) :unknown))
               (when (:job-id b) (str " (" (:job-id b) ")")))])]]
     [:div
      [:button {:type "submit"
                :class "w-full bg-blue-600 text-white px-4 py-2 rounded-md text-sm font-medium
                        hover:bg-blue-700 active:bg-blue-800 transition-colors
                        focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"}
       "Compare"]]]]])

(defn- render-summary-section
  "Render the comparison summary card."
  [build-a build-b summary]
  [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 mb-6"}
   [:div {:class "px-5 py-4 border-b"}
    [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100"} "Comparison Summary"]]
   [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 p-5 text-sm"}
    [:div
     [:span {:class "text-gray-500 dark:text-gray-400 block"} "Build A"]
     [:div {:class "flex items-center gap-2"}
      [:a {:href (str "/builds/" (:id build-a))
           :class "text-blue-600 hover:underline font-mono font-medium"}
       (str "#" (:build-number build-a))]
      (c/status-badge (:status build-a))]]
    [:div
     [:span {:class "text-gray-500 dark:text-gray-400 block"} "Build B"]
     [:div {:class "flex items-center gap-2"}
      [:a {:href (str "/builds/" (:id build-b))
           :class "text-blue-600 hover:underline font-mono font-medium"}
       (str "#" (:build-number build-b))]
      (c/status-badge (:status build-b))]]
    [:div
     [:span {:class "text-gray-500 dark:text-gray-400 block"} "Status Changed?"]
     [:span {:class (str "font-medium " (if (:status-changed? summary) "text-red-600" "text-green-600"))}
      (if (:status-changed? summary) "Yes" "No")]]
    [:div
     [:span {:class "text-gray-500 dark:text-gray-400 block"} "Duration Delta"]
     (if-let [delta (:duration-delta-s summary)]
       (delta-badge delta)
       [:span {:class "text-gray-400"} "-"])]]
   [:div {:class "grid grid-cols-2 gap-4 px-5 pb-5 text-sm"}
    [:div
     [:span {:class "text-gray-500 dark:text-gray-400 block"} "Duration A"]
     [:span {:class "font-mono"} (or (format-duration (:duration-a-s summary)) "-")]]
    [:div
     [:span {:class "text-gray-500 dark:text-gray-400 block"} "Duration B"]
     [:span {:class "font-mono"} (or (format-duration (:duration-b-s summary)) "-")]]]
   (when (or (seq (:stages-added summary)) (seq (:stages-removed summary)))
     [:div {:class "px-5 pb-5 text-sm"}
      (when (seq (:stages-added summary))
        [:div {:class "mb-2"}
         [:span {:class "text-gray-500 dark:text-gray-400"} "Stages added in B: "]
         (for [s (:stages-added summary)]
           [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium
                           bg-blue-100 text-blue-700 mr-1"} s])])
      (when (seq (:stages-removed summary))
        [:div
         [:span {:class "text-gray-500 dark:text-gray-400"} "Stages removed in B: "]
         (for [s (:stages-removed summary)]
           [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium
                           bg-orange-100 text-orange-700 mr-1"} s])])])])

(defn- render-step-row
  "Render a single step comparison row."
  [step]
  (let [step-class (status-match-class (:status-a step) (:status-b step))]
    [:tr {:class (str "hover:bg-gray-50 text-xs " step-class)}
     [:td {:class "px-5 py-2 pl-10 text-gray-600"}
      (:step-name step)
      (when (or (:exit-code-a step) (:exit-code-b step))
        [:span {:class "text-gray-400 font-mono ml-2"}
         (str "exit: " (or (:exit-code-a step) "-") " / " (or (:exit-code-b step) "-"))])]
     [:td {:class "px-5 py-2"} (status-or-dash (:status-a step))]
     [:td {:class "px-5 py-2"} (status-or-dash (:status-b step))]
     [:td {:class "px-5 py-2 font-mono text-gray-500"} (or (format-duration (:duration-a-s step)) "-")]
     [:td {:class "px-5 py-2 font-mono text-gray-500"} (or (format-duration (:duration-b-s step)) "-")]
     [:td {:class "px-5 py-2"} (delta-badge (:duration-delta-s step))]]))

(defn- render-stage-rows
  "Render stage row + its step rows for the comparison table."
  [stage]
  (let [row-class (status-match-class (:status-a stage) (:status-b stage))]
    (list
      [:tr {:class (str "hover:bg-gray-50 " row-class)}
       [:td {:class "px-5 py-3 font-medium text-gray-900 dark:text-gray-100"} (:stage-name stage)]
       [:td {:class "px-5 py-3"} (status-or-dash (:status-a stage))]
       [:td {:class "px-5 py-3"} (status-or-dash (:status-b stage))]
       [:td {:class "px-5 py-3 font-mono text-gray-600"} (or (format-duration (:duration-a-s stage)) "-")]
       [:td {:class "px-5 py-3 font-mono text-gray-600"} (or (format-duration (:duration-b-s stage)) "-")]
       [:td {:class "px-5 py-3"} (delta-badge (:duration-delta-s stage))]]
      (when-let [steps (:steps stage)]
        (for [step steps]
          (render-step-row step))))))

(defn- render-stage-comparison
  "Render the stage-by-stage comparison table."
  [stages]
  [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 mb-6"}
   [:div {:class "px-5 py-4 border-b"}
    [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100"} "Stage Comparison"]]
   (if (empty? stages)
     [:div {:class "p-5 text-gray-400 dark:text-gray-500"} "No stages to compare."]
     [:div {:class "p-0"}
      [:table {:class "w-full text-sm"}
       [:thead
        [:tr {:class "text-left text-gray-500 dark:text-gray-400 border-b dark:border-gray-700 bg-gray-50 dark:bg-gray-800"}
         [:th {:class "px-5 py-2 font-medium"} "Stage"]
         [:th {:class "px-5 py-2 font-medium"} "Status A"]
         [:th {:class "px-5 py-2 font-medium"} "Status B"]
         [:th {:class "px-5 py-2 font-medium"} "Duration A"]
         [:th {:class "px-5 py-2 font-medium"} "Duration B"]
         [:th {:class "px-5 py-2 font-medium"} "Delta"]]]
       [:tbody {:class "divide-y"}
        (for [stage stages]
          (render-stage-rows stage))]]])])

(defn- render-artifacts-comparison
  "Render the artifacts comparison section."
  [artifacts]
  (when (or (seq (:only-in-a artifacts)) (seq (:only-in-b artifacts))
            (seq (:in-both artifacts)) (seq (:size-changes artifacts)))
    [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 mb-6"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100"} "Artifacts Comparison"]]
     [:div {:class "p-5 space-y-4"}
      (when (seq (:only-in-a artifacts))
        [:div
         [:h3 {:class "text-sm font-medium text-orange-700 mb-2"}
          (str "Only in Build A (" (count (:only-in-a artifacts)) ")")]
         [:div {:class "flex flex-wrap gap-2"}
          (for [f (:only-in-a artifacts)]
            [:span {:class "inline-flex items-center px-2 py-1 rounded text-xs font-mono
                            bg-orange-50 text-orange-700 border border-orange-200"} f])]])
      (when (seq (:only-in-b artifacts))
        [:div
         [:h3 {:class "text-sm font-medium text-blue-700 mb-2"}
          (str "Only in Build B (" (count (:only-in-b artifacts)) ")")]
         [:div {:class "flex flex-wrap gap-2"}
          (for [f (:only-in-b artifacts)]
            [:span {:class "inline-flex items-center px-2 py-1 rounded text-xs font-mono
                            bg-blue-50 text-blue-700 border border-blue-200"} f])]])
      (when (seq (:in-both artifacts))
        [:div
         [:h3 {:class "text-sm font-medium text-gray-700 mb-2"}
          (str "In Both (" (count (:in-both artifacts)) ")")]
         [:div {:class "flex flex-wrap gap-2"}
          (for [f (:in-both artifacts)]
            [:span {:class "inline-flex items-center px-2 py-1 rounded text-xs font-mono
                            bg-gray-50 text-gray-600 border border-gray-200"} f])]])
      (when (seq (:size-changes artifacts))
        [:div
         [:h3 {:class "text-sm font-medium text-gray-700 mb-2"} "Size Changes"]
         [:table {:class "w-full text-sm"}
          [:thead
           [:tr {:class "text-left text-gray-500 dark:text-gray-400 border-b dark:border-gray-700 bg-gray-50 dark:bg-gray-800"}
            [:th {:class "px-3 py-2 font-medium"} "File"]
            [:th {:class "px-3 py-2 font-medium"} "Size A"]
            [:th {:class "px-3 py-2 font-medium"} "Size B"]
            [:th {:class "px-3 py-2 font-medium"} "Delta"]]]
          [:tbody {:class "divide-y"}
           (for [sc (:size-changes artifacts)]
             [:tr {:class "hover:bg-gray-50"}
              [:td {:class "px-3 py-2 font-mono"} (:filename sc)]
              [:td {:class "px-3 py-2 text-gray-600"} (str (:size-a sc) " B")]
              [:td {:class "px-3 py-2 text-gray-600"} (str (:size-b sc) " B")]
              [:td {:class "px-3 py-2"}
               (let [d (:delta sc)
                     color (if (pos? d) "text-red-600" "text-green-600")
                     sign (if (pos? d) "+" "")]
                 [:span {:class (str "font-mono " color)} (str sign d " B")])]])]]])]]))

(defn render-compare-results
  "Comparison results fragment."
  [{:keys [comparison]}]
  (let [{:keys [build-a build-b summary stages artifacts]} comparison]
    [:div
     (render-summary-section build-a build-b summary)
     (render-stage-comparison stages)
     (render-artifacts-comparison artifacts)]))

(defn render-compare-page
  "Build comparison page with side-by-side view."
  [{:keys [csrf-token user auth-enabled comparison builds-list job-name build-a-id build-b-id]}]
  (layout/base-layout
    {:title "Compare Builds" :csrf-token csrf-token
     :user user :auth-enabled auth-enabled}
    (c/page-header "Compare Builds"
                   [:a {:href "/" :class "text-sm text-blue-600 hover:underline"} "Dashboard"])
    (render-compare-select {:builds-list builds-list
                            :job-name job-name
                            :build-a-id build-a-id
                            :build-b-id build-b-id})
    (when comparison
      (render-compare-results {:comparison comparison}))))
