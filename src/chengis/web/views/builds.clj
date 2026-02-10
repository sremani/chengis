(ns chengis.web.views.builds
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [chengis.web.views.pipeline-viz :as pipeline-viz]
            [chengis.engine.dag :as dag]
            [hiccup.util :refer [escape-html]]))

(defn- render-git-info-section
  "Render git metadata card if git info is present in the build."
  [build]
  (when (:git-commit build)
    [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Source"]]
     [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 p-5 text-sm"}
      [:div
       [:span {:class "text-gray-500 block"} "Branch"]
       [:span {:class "font-mono font-medium bg-blue-50 text-blue-700 px-2 py-0.5 rounded text-sm"}
        (or (:git-branch build) "-")]]
      [:div
       [:span {:class "text-gray-500 block"} "Commit"]
       [:span {:class "font-mono text-sm"}
        (or (:git-commit-short build) "-")]]
      [:div
       [:span {:class "text-gray-500 block"} "Author"]
       [:span {:class "font-medium"} (or (:git-author build) "-")]]
      [:div
       [:span {:class "text-gray-500 block"} "Message"]
       [:span {:class "text-sm text-gray-700 truncate block max-w-xs"}
        (or (:git-message build) "-")]]]]))

(defn- render-stages-section
  "Render the stages & steps section."
  [stages steps]
  [:div {:class "bg-white rounded-lg shadow-sm border mb-6"
         :id "stages-container"}
   [:div {:class "px-5 py-4 border-b"}
    [:h2 {:class "text-lg font-semibold text-gray-900"} "Stages"]]
   [:div {:class "divide-y" :id "stages-list"}
    (if (empty? stages)
      [:div {:class "p-5 text-gray-400"} "No stage data available."]
      (for [stage stages]
        (let [stage-steps (filter #(= (:stage-name %) (:stage-name stage)) steps)]
          [:div {:class "p-5" :id (str "stage-" (:stage-name stage))}
           [:div {:class "flex items-center justify-between mb-3"}
            [:span {:class "font-medium text-gray-900"} (:stage-name stage)]
            (c/status-badge (:status stage))]
           (when (seq stage-steps)
             [:div {:class "ml-4 space-y-2"}
              (for [step stage-steps]
                [:div {:class "flex items-center gap-3 text-sm"
                       :id (str "step-" (:stage-name step) "-" (:step-name step))}
                 (c/status-badge (:status step))
                 [:span {:class "text-gray-700"} (:step-name step)]
                 (when (:exit-code step)
                   [:span {:class "text-gray-400 font-mono text-xs"}
                    (str "exit: " (:exit-code step))])])])])))]])

(defn- render-matrix-grid
  "Render a compact grid of matrix build results, if stages have matrix labels."
  [stages]
  (let [;; Detect matrix stages: they have names like "Build [os=linux, jdk=11]"
        matrix-stages (filter #(re-find #"\[.*=.*\]" (or (:stage-name %) "")) stages)]
    (when (seq matrix-stages)
      [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
       [:div {:class "px-5 py-4 border-b"}
        [:h2 {:class "text-lg font-semibold text-gray-900"} "Matrix Results"]]
       [:div {:class "p-5 flex flex-wrap gap-2"}
        (for [stage matrix-stages]
          (let [label (second (re-find #"\[(.*)\]" (or (:stage-name stage) "")))
                status-class (case (keyword (or (:status stage) "pending"))
                               :success "bg-green-100 text-green-800 border-green-300"
                               :failure "bg-red-100 text-red-800 border-red-300"
                               :running "bg-blue-100 text-blue-800 border-blue-300"
                               :aborted "bg-yellow-100 text-yellow-800 border-yellow-300"
                               "bg-gray-100 text-gray-600 border-gray-300")
                icon (case (keyword (or (:status stage) "pending"))
                      :success "\u2713"
                      :failure "\u2717"
                      :running "\u25CB"
                      :aborted "\u25CB"
                      "\u25CB")]
            [:span {:class (str "inline-flex items-center gap-1 px-3 py-1.5 rounded border text-xs font-mono " status-class)}
             [:span icon]
             (escape-html (or label (:stage-name stage)))]))]])))

(defn- render-output-section
  "Render the build output / log section."
  [build-id running? steps]
  [:div {:class "bg-white rounded-lg shadow-sm border"}
   [:div {:class "px-5 py-4 border-b flex items-center justify-between"}
    [:h2 {:class "text-lg font-semibold text-gray-900"} "Build Output"]
    [:a {:href (str "/builds/" build-id "/log")
         :class "text-sm text-blue-600 hover:underline"} "Full log"]]
   [:div {:id "build-log"
          :class "bg-gray-900 text-gray-100 font-mono text-sm p-4 rounded-b-lg
                  max-h-[600px] overflow-y-auto log-container"
          :sse-swap (when running? "log-line")
          :hx-swap (when running? "beforeend")}
    (for [step steps :when (or (seq (:stdout step)) (seq (:stderr step)))]
      [:div {:class "mb-3"}
       [:div {:class "text-blue-400 font-bold text-xs mb-1"}
        (str "--- " (:stage-name step) " / " (:step-name step) " ---")]
       (when (seq (:stdout step))
         [:pre {:class "whitespace-pre-wrap text-green-300"} (escape-html (:stdout step))])
       (when (seq (:stderr step))
         [:pre {:class "whitespace-pre-wrap text-red-400"} (escape-html (:stderr step))])])]])

(defn- render-artifacts-section
  "Render the artifacts table if artifacts exist."
  [build-id artifacts]
  (when (seq artifacts)
    [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
     [:div {:class "px-5 py-4 border-b flex items-center justify-between"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Artifacts"]
      [:span {:class "text-xs text-gray-400"} (str (count artifacts) " files")]]
     [:div {:class "p-0"}
      [:table {:class "w-full text-sm"}
       [:thead
        [:tr {:class "text-left text-gray-500 border-b bg-gray-50"}
         [:th {:class "px-5 py-2 font-medium"} "Filename"]
         [:th {:class "px-5 py-2 font-medium"} "Size"]
         [:th {:class "px-5 py-2 font-medium"} "SHA-256"]
         [:th {:class "px-5 py-2 font-medium"} "Type"]
         [:th {:class "px-5 py-2 font-medium"} ""]]]
       [:tbody {:class "divide-y"}
        (for [art artifacts]
          [:tr {:class "hover:bg-gray-50"}
           [:td {:class "px-5 py-3 font-mono text-sm"}
            (:filename art)]
           [:td {:class "px-5 py-3 text-gray-500"}
            (let [bytes (:size-bytes art)]
              (cond
                (nil? bytes) "—"
                (< bytes 1024) (str bytes " B")
                (< bytes (* 1024 1024)) (format "%.1f KB" (/ bytes 1024.0))
                :else (format "%.1f MB" (/ bytes (* 1024.0 1024.0)))))]
           [:td {:class "px-5 py-3"}
            (if-let [hash (:sha256-hash art)]
              [:code {:class "text-xs text-gray-500 bg-gray-100 px-1.5 py-0.5 rounded"
                      :title hash}
               (subs hash 0 (min 16 (count hash)))]
              [:span {:class "text-gray-300 text-xs"} "—"])]
           [:td {:class "px-5 py-3 text-gray-400 text-xs"}
            (or (:content-type art) "—")]
           [:td {:class "px-5 py-3 text-right"}
            [:a {:href (str "/builds/" build-id "/artifacts/" (:filename art))
                 :class "text-blue-600 hover:text-blue-800 hover:underline text-sm"}
             "Download"]]])]]]]))

(defn- render-notifications-section
  "Render the notifications section if any notifications exist."
  [notifications]
  (when (seq notifications)
    [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Notifications"]]
     [:div {:class "p-5"}
      [:div {:class "space-y-2"}
       (for [notif notifications]
         [:div {:class "flex items-center gap-3 text-sm"}
          (case (:status notif)
            "sent"    [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium
                                     bg-green-100 text-green-700"} "sent"]
            "failed"  [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium
                                     bg-red-100 text-red-700"} "failed"]
            [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium
                            bg-gray-100 text-gray-700"} (or (:status notif) "pending")])
          [:span {:class "text-gray-700 capitalize"} (:type notif)]
          (when (:details notif)
            [:span {:class "text-gray-400 text-xs truncate max-w-xs"} (:details notif)])])]]]))

(defn- render-retry-history
  "Render the retry history section showing all attempts in the chain."
  [attempts current-build-id]
  (when (and (seq attempts) (> (count attempts) 1))
    [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
     [:div {:class "px-5 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Retry History"]]
     [:div {:class "p-0"}
      [:table {:class "w-full text-sm"}
       [:thead
        [:tr {:class "text-left text-gray-500 border-b bg-gray-50"}
         [:th {:class "px-5 py-2 font-medium"} "Attempt"]
         [:th {:class "px-5 py-2 font-medium"} "Build"]
         [:th {:class "px-5 py-2 font-medium"} "Status"]
         [:th {:class "px-5 py-2 font-medium"} "Trigger"]
         [:th {:class "px-5 py-2 font-medium"} "Started"]]]
       [:tbody {:class "divide-y"}
        (for [attempt attempts]
          (let [is-current? (= (:id attempt) current-build-id)]
            [:tr {:class (if is-current? "bg-blue-50" "hover:bg-gray-50")}
             [:td {:class "px-5 py-3 font-medium"}
              (str "#" (or (:attempt-number attempt) 1))
              (when is-current?
                [:span {:class "ml-1 text-xs text-blue-600"} "(current)"])]
             [:td {:class "px-5 py-3"}
              [:a {:href (str "/builds/" (:id attempt))
                   :class "text-blue-600 hover:underline font-mono text-sm"}
               (str "Build #" (:build-number attempt))]]
             [:td {:class "px-5 py-3"} (c/status-badge (:status attempt))]
             [:td {:class "px-5 py-3 text-gray-500"}
              (or (:trigger-type attempt) "manual")]
             [:td {:class "px-5 py-3 text-gray-400 font-mono text-xs"}
              (or (:started-at attempt) "-")]]))]]]]))

(defn render-detail
  "Build detail page with stages, steps, and log output.
   For running builds, includes SSE connection for live updates."
  [{:keys [build stages steps job artifacts notifications attempts csrf-token]}]
  (let [build-id (:id build)
        running? (= :running (:status build))
        attempt-num (or (:attempt-number build) 1)]
    (layout/base-layout
      {:title (str "Build #" (:build-number build)) :csrf-token csrf-token}
      [:div (when running?
              {:hx-ext "sse"
               :sse-connect (str "/api/builds/" build-id "/events")})

       ;; Header
       [:div {:class "flex items-center justify-between mb-6"}
        [:div
         [:h1 {:class "text-2xl font-bold text-gray-900"}
          (str "Build #" (:build-number build))
          ;; Attempt badge (only show when attempt > 1)
          (when (> attempt-num 1)
            [:span {:class "ml-2 inline-flex items-center px-2 py-0.5 rounded text-xs font-medium
                            bg-orange-100 text-orange-700 border border-orange-200"}
             (str "Attempt #" attempt-num)])]
         [:p {:class "text-sm text-gray-500 mt-1"}
          (str "Job: ")
          [:a {:href (str "/jobs/" (or (:name job) ""))
               :class "text-blue-600 hover:underline"}
           (or (:name job) (:job-id build))]
          ;; Retry lineage
          (when-let [parent-id (:parent-build-id build)]
            [:span {:class "ml-2 text-gray-400"}
             "Retried from "
             [:a {:href (str "/builds/" parent-id)
                  :class "text-blue-500 hover:underline"} "parent build"]])]]
        [:div {:class "flex items-center gap-3" :id "build-status"}
         ;; Cancel button (only for running/queued builds)
         (when (#{:running :queued} (:status build))
           [:form {:method "post" :action (str "/builds/" build-id "/cancel")}
            [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
            [:button {:type "submit"
                      :class "bg-red-600 text-white px-3 py-1.5 rounded-md text-sm font-medium
                              hover:bg-red-700 active:bg-red-800 transition-colors
                              focus:outline-none focus:ring-2 focus:ring-red-500"
                      :onclick "return confirm('Cancel this build?')"}
             "Cancel Build"]])
         ;; Retry button (only for completed builds)
         (when (#{:success :failure :aborted} (:status build))
           [:form {:method "post" :action (str "/builds/" build-id "/retry")}
            [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
            [:button {:type "submit"
                      :class "bg-yellow-600 text-white px-3 py-1.5 rounded-md text-sm font-medium
                              hover:bg-yellow-700 active:bg-yellow-800 transition-colors
                              focus:outline-none focus:ring-2 focus:ring-yellow-500"}
             "Retry"]])
         ;; Compare button
         [:a {:href (str "/compare?a=" build-id)
              :class "bg-gray-600 text-white px-3 py-1.5 rounded-md text-sm font-medium
                      hover:bg-gray-700 active:bg-gray-800 transition-colors
                      focus:outline-none focus:ring-2 focus:ring-gray-500 no-underline"}
          "Compare"]
         (c/status-badge (:status build))]]

       ;; Build info
       [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
        [:div {:class "grid grid-cols-2 md:grid-cols-5 gap-4 p-5 text-sm"}
         [:div
          [:span {:class "text-gray-500 block"} "Trigger"]
          [:span {:class "font-medium"} (or (:trigger-type build) "manual")]]
         [:div
          [:span {:class "text-gray-500 block"} "Pipeline Source"]
          (let [source (or (:pipeline-source build) "server")]
            (if (= source "chengisfile")
              [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium
                              bg-purple-100 text-purple-700"}
               "Chengisfile"]
              [:span {:class "font-medium text-sm"} "Server"]))]
         [:div
          [:span {:class "text-gray-500 block"} "Started"]
          [:span {:class "font-mono"} (or (:started-at build) "-")]]
         [:div
          [:span {:class "text-gray-500 block"} "Completed"]
          [:span {:class "font-mono"} (or (:completed-at build) "-")]]
         [:div
          [:span {:class "text-gray-500 block"} "Workspace"]
          [:span {:class "font-mono text-xs"} (or (:workspace build) "-")]]]]

       ;; Build parameters (if any)
       (when-let [params (:parameters build)]
         (when (and (map? params) (seq params))
           [:div {:class "bg-white rounded-lg shadow-sm border mb-6"}
            [:div {:class "px-5 py-4 border-b"}
             [:h2 {:class "text-lg font-semibold text-gray-900"} "Parameters"]]
            [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-4 p-5 text-sm"}
             (for [[k v] (sort-by key params)]
               [:div
                [:span {:class "text-gray-500 block font-mono text-xs"} (name k)]
                [:span {:class "font-medium"} (str v)]])]]))

       ;; Matrix results grid (if matrix build)
       (render-matrix-grid stages)

       ;; Pipeline visualization (with live status)
       ;; Use DAG viz when pipeline has dependencies, otherwise linear graph
       (when (seq stages)
         (let [stage-defs (mapv (fn [s] {:stage-name (:stage-name s)
                                          :steps (filter #(= (:stage-name %) (:stage-name s)) steps)
                                          :parallel? false})
                                stages)]
           (if (dag/has-dag? stage-defs)
             (pipeline-viz/render-dag-pipeline stage-defs
                                              {:stage-results stages
                                               :step-results steps})
             (c/pipeline-graph stage-defs
                               {:stage-results stages
                                :step-results steps}))))

       ;; Git info (only for git-sourced builds)
       (render-git-info-section build)

       ;; Retry history (when build is part of a retry chain)
       (render-retry-history attempts build-id)

       ;; Artifacts (if any)
       (render-artifacts-section (:id build) artifacts)

       ;; Notifications (if any)
       (render-notifications-section notifications)

       ;; Stages & Steps
       (render-stages-section stages steps)

       ;; Build output
       (render-output-section build-id running? steps)])))

(defn render-log
  "Full build log page — all step stdout/stderr."
  [{:keys [build steps csrf-token]}]
  (layout/base-layout
    {:title (str "Build #" (:build-number build) " Log") :csrf-token csrf-token}
    (c/page-header (str "Build #" (:build-number build) " - Full Log")
                   [:a {:href (str "/builds/" (:id build))
                        :class "text-sm text-blue-600 hover:underline"} "Back to build"])
    [:div {:class "bg-gray-900 rounded-lg shadow-sm border text-gray-100 font-mono text-sm p-6 overflow-x-auto"}
     (if (empty? steps)
       [:div {:class "text-gray-500"} "No log output available."]
       (for [step steps]
         [:div {:class "mb-6"}
          [:div {:class "text-blue-400 font-bold text-xs mb-2 border-b border-gray-700 pb-1"}
           (str "--- " (:stage-name step) " / " (:step-name step)
                " [" (:status step) "] exit:" (:exit-code step) " ---")]
          (when (seq (:stdout step))
            [:pre {:class "whitespace-pre-wrap text-green-300"} (escape-html (:stdout step))])
          (when (seq (:stderr step))
            [:pre {:class "whitespace-pre-wrap text-red-400 mt-1"} (escape-html (:stderr step))])]))]))
