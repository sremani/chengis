(ns chengis.web.views.pipeline-viz
  "DAG-style pipeline visualization.
   Computes stage depths server-side and renders a CSS grid/flexbox layout
   with SVG arrow connectors. Zero JavaScript — everything is pure HTML+CSS+SVG."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [chengis.engine.dag :as dag]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

;; ---------------------------------------------------------------------------
;; Layout computation
;; ---------------------------------------------------------------------------

(defn compute-dag-layout
  "Compute column positions for DAG visualization.
   Each stage is assigned a depth (column index):
     depth = 0 if no dependencies
     depth = 1 + max(depth of dependencies) otherwise
   Returns a vector of column maps sorted by column index:
     [{:column 0 :stages [stage-def ...]} {:column 1 :stages [...]} ...]"
  [stages]
  (if (empty? stages)
    []
    (let [;; Build name -> stage-def lookup
          by-name (into {} (map (juxt :stage-name identity) stages))
          ;; Compute depth for each stage using memoized recursive function
          depths (atom {})
          compute-depth
          (fn compute-depth [stage-name]
            (if-let [cached (get @depths stage-name)]
              cached
              (let [stage (get by-name stage-name)
                    deps (or (:depends-on stage) [])
                    d (if (empty? deps)
                        0
                        (inc (apply max (map compute-depth deps))))]
                (swap! depths assoc stage-name d)
                d)))]
      ;; Compute depth for every stage (sequential pipelines: each stage
      ;; gets its own column since there are no deps)
      (if (dag/has-dag? stages)
        ;; DAG mode: compute depth from dependencies
        (do
          (doseq [s stages]
            (compute-depth (:stage-name s)))
          (let [grouped (group-by (fn [s] (get @depths (:stage-name s))) stages)
                max-col (apply max (keys grouped))]
            (mapv (fn [col]
                    {:column col
                     :stages (or (get grouped col) [])})
                  (range (inc max-col)))))
        ;; Sequential mode: one stage per column in order
        (mapv (fn [idx stage]
                {:column idx :stages [stage]})
              (range) stages)))))

;; ---------------------------------------------------------------------------
;; DAG edge computation
;; ---------------------------------------------------------------------------

(defn- compute-edges
  "Compute directed edges for the DAG.
   Returns a seq of [from-stage-name to-stage-name] pairs
   where from is a dependency and to is the dependent."
  [stages]
  (for [stage stages
        dep (or (:depends-on stage) [])
        :when dep]
    [dep (:stage-name stage)]))

(defn- compute-sequential-edges
  "For non-DAG pipelines, compute sequential edges (stage[i] -> stage[i+1])."
  [stages]
  (when (> (count stages) 1)
    (mapv (fn [[a b]] [(:stage-name a) (:stage-name b)])
          (partition 2 1 stages))))

;; ---------------------------------------------------------------------------
;; Rendering helpers
;; ---------------------------------------------------------------------------

(defn- stage-status-classes
  "Return border + bg Tailwind classes for a stage node based on status."
  [status]
  (case status
    :success  "border-green-400 bg-green-50 dark:bg-green-900/30"
    :failure  "border-red-400 bg-red-50 dark:bg-red-900/30"
    :running  "border-blue-400 bg-blue-50 dark:bg-blue-900/30 animate-pulse"
    :aborted  "border-orange-400 bg-orange-50 dark:bg-orange-900/30"
    :skipped  "border-gray-300 dark:border-gray-600 bg-gray-50 dark:bg-gray-800"
    :queued   "border-yellow-400 bg-yellow-50 dark:bg-yellow-900/30"
    ;; Default (no status / pending / structural view)
    "border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800"))

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

(defn- dag-stage-node
  "Render a single stage node for the DAG visualization.
   stage-def: {:stage-name ... :steps [...] :depends-on [...] :parallel? ...}
   stage-status: optional {:status :success} or nil
   step-statuses: optional seq of {:step-name ... :status ...} or nil
   show-details?: whether to show individual steps"
  [stage-def stage-status step-statuses show-details?]
  (let [status (:status stage-status)
        border-classes (stage-status-classes status)
        post? (str/starts-with? (or (:stage-name stage-def) "") "post:")
        stage-name (:stage-name stage-def)]
    [:div {:class (str "w-48 rounded-lg border-2 p-3 shadow-sm relative " border-classes)
           :id (str "dag-stage-" stage-name)
           :data-stage stage-name}
     ;; Stage name + badges
     [:div {:class "flex items-center justify-between mb-1"}
      [:span {:class "font-semibold text-sm text-gray-900 dark:text-gray-100 truncate"} stage-name]
      [:div {:class "flex items-center gap-1"}
       (when (:parallel? stage-def)
         [:span {:class "text-[10px] bg-blue-100 text-blue-700 px-1 rounded"} "P"])
       (when (seq (:depends-on stage-def))
         [:span {:class "text-[10px] bg-indigo-100 text-indigo-700 px-1 rounded"} "DAG"])
       (when post?
         [:span {:class "text-[10px] bg-purple-100 text-purple-700 px-1 rounded"} "post"])]]
     ;; Steps list (when showing details)
     (when show-details?
       (let [steps (or (:steps stage-def) [])
             status-map (when step-statuses
                          (into {} (map (juxt :step-name :status) step-statuses)))]
         (when (seq steps)
           [:div {:class "space-y-1 mt-1"}
            (for [step steps]
              [:div {:class "flex items-center gap-1.5 text-xs text-gray-600"}
               (if status-map
                 (step-status-dot (get status-map (:step-name step)))
                 [:span {:class "inline-block w-2 h-2 rounded-full bg-gray-300"}])
               [:span {:class "truncate"} (:step-name step)]])])))
     ;; Status badge at bottom
     (when status
       [:div {:class "mt-2 pt-2 border-t border-gray-200 dark:border-gray-600"}
        (c/status-badge status)])]))

;; ---------------------------------------------------------------------------
;; SVG arrow rendering
;; ---------------------------------------------------------------------------

;; Node geometry constants (in px) — must match Tailwind classes above
(def ^:private node-width 192)   ;; w-48 = 12rem = 192px
(def ^:private node-height 80)   ;; approximate height for a node
(def ^:private col-gap 80)       ;; gap between columns
(def ^:private row-gap 24)       ;; gap between rows in same column

(defn- stage-positions
  "Compute {stage-name {:x px :y px}} for SVG arrow endpoints.
   Columns are laid out left-to-right, stages within a column top-to-bottom."
  [layout]
  (into {}
    (for [{:keys [column stages]} layout
          :let [x (* column (+ node-width col-gap))]
          [row-idx stage] (map-indexed vector stages)
          :let [y (* row-idx (+ node-height row-gap))]]
      [(:stage-name stage) {:x x :y y :col column :row row-idx}])))

(defn- render-dag-arrows
  "Render SVG arrows between dependent stages.
   Draws a curved line from the right edge of the source node to the left edge
   of the target node."
  [layout edges]
  (let [positions (stage-positions layout)
        total-cols (count layout)
        max-rows (apply max 1 (map (fn [{:keys [stages]}] (count stages)) layout))
        svg-width (+ (* total-cols (+ node-width col-gap)) 20)
        svg-height (+ (* max-rows (+ node-height row-gap)) 20)]
    [:svg {:class "absolute inset-0 pointer-events-none"
           :style (str "width:" svg-width "px;height:" svg-height "px;z-index:0;")
           :viewBox (str "0 0 " svg-width " " svg-height)
           :fill "none"}
     (for [[from to] edges
           :let [from-pos (get positions from)
                 to-pos (get positions to)]
           :when (and from-pos to-pos)]
       (let [;; Arrow starts at right-center of source node
             x1 (+ (:x from-pos) node-width)
             y1 (+ (:y from-pos) (/ node-height 2))
             ;; Arrow ends at left-center of target node
             x2 (:x to-pos)
             y2 (+ (:y to-pos) (/ node-height 2))
             ;; Curved path using a horizontal S-curve
             mid-x (/ (+ x1 x2) 2)]
         [:g {:key (str from "->" to)}
          [:path {:d (str "M" x1 "," y1
                          " C" mid-x "," y1
                          " " mid-x "," y2
                          " " x2 "," y2)
                  :stroke "#9CA3AF"
                  :stroke-width "2"
                  :fill "none"}]
          ;; Arrowhead
          [:polygon {:points (str x2 "," y2
                                  " " (- x2 8) "," (- y2 4)
                                  " " (- x2 8) "," (+ y2 4))
                     :fill "#9CA3AF"}]]))]))

;; ---------------------------------------------------------------------------
;; Main rendering
;; ---------------------------------------------------------------------------

(defn render-dag-pipeline
  "Render a full DAG pipeline visualization.
   stages: seq of stage defs [{:stage-name ... :steps [...] :depends-on [...]}]
   opts: {:stage-results [...] :step-results [...] :show-details? bool}"
  [stages & [opts]]
  (if (empty? stages)
    [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 mb-6 p-5 text-gray-400 dark:text-gray-500"}
     "No pipeline stages defined."]
    (let [{:keys [stage-results step-results show-details?]
           :or {show-details? true}} opts
          is-dag? (dag/has-dag? stages)
          layout (compute-dag-layout stages)
          edges (if is-dag?
                  (compute-edges stages)
                  (compute-sequential-edges stages))
          status-by-stage (when stage-results
                            (into {} (map (juxt :stage-name identity) stage-results)))
          steps-by-stage (when step-results
                           (group-by :stage-name step-results))
          positions (stage-positions layout)
          total-cols (count layout)
          max-rows (apply max 1 (map (fn [{:keys [stages]}] (count stages)) layout))
          svg-width (+ (* total-cols (+ node-width col-gap)) 20)
          svg-height (+ (* max-rows (+ node-height row-gap)) 20)]
      [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 mb-6" :id "dag-pipeline-viz"}
       [:div {:class "px-5 py-4 border-b flex items-center justify-between"}
        [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100"} "Pipeline"]
        (when is-dag?
          [:span {:class "text-xs bg-indigo-100 text-indigo-700 px-2 py-0.5 rounded-full font-medium"}
           "DAG"])]
       [:div {:class "p-5 overflow-x-auto"}
        [:div {:class "relative"
               :style (str "min-width:" svg-width "px;min-height:" svg-height "px;")}
         ;; SVG arrow layer (behind nodes)
         (render-dag-arrows layout edges)
         ;; Node layer (on top of arrows)
         (for [{:keys [column stages]} layout]
           (for [[row-idx stage] (map-indexed vector stages)]
             (let [pos (get positions (:stage-name stage))
                   x (:x pos)
                   y (:y pos)]
               [:div {:class "absolute"
                      :style (str "left:" x "px;top:" y "px;z-index:1;")}
                (dag-stage-node
                  stage
                  (get status-by-stage (:stage-name stage))
                  (get steps-by-stage (:stage-name stage))
                  show-details?)])))]]])))

;; ---------------------------------------------------------------------------
;; Full pipeline detail page
;; ---------------------------------------------------------------------------

(defn render-pipeline-detail-page
  "Full page showing pipeline visualization for a job.
   data: {:job job :pipeline pipeline :csrf-token token :user user :auth-enabled bool}"
  [{:keys [job pipeline csrf-token user auth-enabled]}]
  (let [stages (or (:stages pipeline) [])
        job-name (:name job)
        encoded-name (URLEncoder/encode (str job-name) "UTF-8")]
    (layout/base-layout
      {:title (str job-name " - Pipeline")
       :csrf-token csrf-token
       :user user
       :auth-enabled auth-enabled}

      ;; Header with breadcrumb
      [:div {:class "flex items-center justify-between mb-6"}
       [:div
        [:div {:class "flex items-center gap-2 text-sm text-gray-500 mb-1"}
         [:a {:href "/jobs" :class "hover:text-blue-600"} "Jobs"]
         [:span "/"]
         [:a {:href (str "/jobs/" encoded-name)
              :class "hover:text-blue-600"} job-name]
         [:span "/"]
         [:span {:class "text-gray-900"} "Pipeline"]]
        [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Pipeline Visualization"]]
       [:a {:href (str "/jobs/" encoded-name)
            :class "text-sm text-blue-600 hover:underline"}
        "Back to Job"]]

      ;; Pipeline metadata
      (when (or (:description pipeline) (get-in pipeline [:source :url]))
        [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 mb-6 p-5"}
         (when (:description pipeline)
           [:p {:class "text-gray-600 dark:text-gray-400"} (:description pipeline)])
         (when (get-in pipeline [:source :url])
           [:div {:class "mt-2"}
            [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium
                            bg-purple-100 text-purple-800 border border-purple-200"}
             "Pipeline as Code"]])])

      ;; Pipeline info summary
      [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 mb-6"}
       [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 p-5 text-sm"}
        [:div
         [:span {:class "text-gray-500 dark:text-gray-400 block"} "Stages"]
         [:span {:class "font-bold text-lg"} (count stages)]]
        [:div
         [:span {:class "text-gray-500 dark:text-gray-400 block"} "Total Steps"]
         [:span {:class "font-bold text-lg"}
          (reduce + (map #(count (or (:steps %) [])) stages))]]
        [:div
         [:span {:class "text-gray-500 dark:text-gray-400 block"} "Layout"]
         [:span {:class "font-medium"}
          (if (dag/has-dag? stages) "DAG (parallel paths)" "Sequential")]]
        [:div
         [:span {:class "text-gray-500 dark:text-gray-400 block"} "Columns"]
         [:span {:class "font-medium"} (count (compute-dag-layout stages))]]]]

      ;; DAG visualization
      (render-dag-pipeline stages {:show-details? true})

      ;; Stage details table
      (when (seq stages)
        [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700"}
         [:div {:class "px-5 py-4 border-b"}
          [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100"} "Stage Details"]]
         [:div {:class "p-0"}
          [:table {:class "w-full text-sm"}
           [:thead
            [:tr {:class "text-left text-gray-500 dark:text-gray-400 border-b dark:border-gray-700 bg-gray-50 dark:bg-gray-800"}
             [:th {:class "px-5 py-2 font-medium"} "Stage"]
             [:th {:class "px-5 py-2 font-medium"} "Steps"]
             [:th {:class "px-5 py-2 font-medium"} "Dependencies"]
             [:th {:class "px-5 py-2 font-medium"} "Flags"]]]
           [:tbody {:class "divide-y"}
            (for [stage stages]
              [:tr {:class "hover:bg-gray-50 dark:hover:bg-gray-700"}
               [:td {:class "px-5 py-3 font-medium text-gray-900 dark:text-gray-100"} (:stage-name stage)]
               [:td {:class "px-5 py-3"}
                (if (seq (:steps stage))
                  [:div {:class "flex flex-wrap gap-1"}
                   (for [step (:steps stage)]
                     [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs
                                     bg-gray-100 text-gray-700 font-mono"}
                      (:step-name step)])]
                  [:span {:class "text-gray-400 dark:text-gray-500"} "none"])]
               [:td {:class "px-5 py-3"}
                (if (seq (:depends-on stage))
                  [:div {:class "flex flex-wrap gap-1"}
                   (for [dep (:depends-on stage)]
                     [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs
                                     bg-indigo-100 text-indigo-700 font-mono"}
                      dep])]
                  [:span {:class "text-gray-400 dark:text-gray-500"} "none"])]
               [:td {:class "px-5 py-3"}
                [:div {:class "flex gap-1"}
                 (when (:parallel? stage)
                   [:span {:class "text-xs bg-blue-100 text-blue-700 px-1.5 py-0.5 rounded"} "parallel"])
                 (when (str/starts-with? (or (:stage-name stage) "") "post:")
                   [:span {:class "text-xs bg-purple-100 text-purple-700 px-1.5 py-0.5 rounded"} "post"])]]])]]]]))))
