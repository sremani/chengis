(ns chengis.web.views.traces
  "Trace listing and waterfall visualization views."
  (:require [chengis.web.views.layout :as layout]))

(defn- status-badge
  "Render a status badge for a span."
  [status]
  (let [color (case status
                "OK" "green"
                "ERROR" "red"
                "gray")]
    [:span {:class (str "inline-block px-2 py-0.5 text-xs font-medium rounded-full "
                        "bg-" color "-100 text-" color "-800")}
     status]))

(defn- duration-bar
  "Render a CSS duration bar for the waterfall chart."
  [width-pct status]
  (let [color (if (= "ERROR" status) "bg-red-500" "bg-blue-500")]
    [:div {:class "flex items-center gap-2 flex-1"}
     [:div {:class "flex-1 bg-gray-100 rounded-full h-3 relative"}
      [:div {:class (str color " rounded-full h-3")
             :style (str "width:" (max 1 width-pct) "%")}]]]))

(defn- format-duration
  "Format duration in ms to a human-readable string."
  [ms]
  (cond
    (nil? ms) "—"
    (< ms 1000) (str ms "ms")
    (< ms 60000) (str (format "%.1f" (/ (double ms) 1000.0)) "s")
    :else (str (format "%.1f" (/ (double ms) 60000.0)) "m")))

(defn- trace-row
  "Render a single trace row in the listing table."
  [trace]
  [:tr {:class "hover:bg-gray-50"}
   [:td {:class "px-4 py-3"}
    [:a {:href (str "/admin/traces/" (:trace-id trace))
         :class "text-blue-600 hover:text-blue-800 font-medium"}
     (:operation trace)]]
   [:td {:class "px-4 py-3 text-xs font-mono text-gray-500"}
    (str (subs (:trace-id trace) 0 (min 12 (count (:trace-id trace)))) "...")]
   [:td {:class "px-4 py-3 text-sm"}
    (when (:build-id trace)
      [:a {:href (str "/builds/" (:build-id trace))
           :class "text-blue-600 hover:text-blue-800"}
       (subs (:build-id trace) 0 (min 8 (count (:build-id trace))))])]
   [:td {:class "px-4 py-3"} (status-badge (:status trace))]
   [:td {:class "px-4 py-3 text-sm text-gray-700"} (format-duration (:duration-ms trace))]
   [:td {:class "px-4 py-3 text-sm text-gray-500"} (:started-at trace)]])

(defn trace-list
  "Render the trace listing page."
  [{:keys [traces csrf-token]}]
  (layout/base-layout
    {:title "Traces" :csrf-token csrf-token}
    [:div {:class "space-y-6"}
     [:div {:class "flex items-center justify-between"}
      [:h1 {:class "text-2xl font-bold text-gray-900"} "Build Traces"]
      [:span {:class "text-sm text-gray-500"} (str (count traces) " traces")]]
     [:div {:class "bg-white rounded-lg shadow-sm border"}
      [:table {:class "min-w-full divide-y divide-gray-200"}
       [:thead {:class "bg-gray-50"}
        [:tr
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Operation"]
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Trace ID"]
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Build"]
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Status"]
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Duration"]
         [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Started"]]]
       [:tbody {:class "divide-y divide-gray-200"}
        (if (empty? traces)
          [:tr [:td {:class "px-4 py-8 text-center text-gray-500" :colspan 6}
                "No traces found. Enable the :tracing feature flag to start collecting traces."]]
          (for [trace traces] (trace-row trace)))]]]]))

(defn- waterfall-span
  "Render a single span in the waterfall chart."
  [span max-duration]
  (let [depth (if (:parent-span-id span) 1 0)
        width-pct (if (and (:duration-ms span) (pos? max-duration))
                    (* 100.0 (/ (double (:duration-ms span)) (double max-duration)))
                    0)]
    [:div {:class "flex items-center gap-3 py-1"}
     [:div {:class "w-48 truncate text-sm"
            :style (str "padding-left:" (* depth 20) "px")}
      [:span {:class "font-medium"} (:operation span)]]
     (duration-bar width-pct (:status span))
     [:div {:class "w-16 text-right text-xs text-gray-500"}
      (format-duration (:duration-ms span))]
     [:div {:class "w-16"} (status-badge (:status span))]]))

(defn trace-detail
  "Render the trace detail page with waterfall visualization."
  [{:keys [trace-id spans csrf-token]}]
  (let [max-duration (or (apply max (keep :duration-ms spans)) 1)]
    (layout/base-layout
      {:title (str "Trace " (subs trace-id 0 (min 12 (count trace-id))))
       :csrf-token csrf-token}
      [:div {:class "space-y-6"}
       [:div {:class "flex items-center justify-between"}
        [:div
         [:h1 {:class "text-2xl font-bold text-gray-900"} "Trace Detail"]
         [:p {:class "text-sm text-gray-500 font-mono"} trace-id]]
        [:a {:href (str "/api/traces/" trace-id "/otlp")
             :class "px-3 py-1.5 bg-gray-100 text-gray-700 rounded-lg text-sm hover:bg-gray-200"}
         "Export OTLP JSON"]]
       ;; Summary stats
       [:div {:class "grid grid-cols-4 gap-4"}
        [:div {:class "bg-white rounded-lg border p-4"}
         [:div {:class "text-sm text-gray-500"} "Spans"]
         [:div {:class "text-2xl font-bold"} (count spans)]]
        [:div {:class "bg-white rounded-lg border p-4"}
         [:div {:class "text-sm text-gray-500"} "Total Duration"]
         [:div {:class "text-2xl font-bold"}
          (format-duration (when-let [root (first (filter #(nil? (:parent-span-id %)) spans))]
                             (:duration-ms root)))]]
        [:div {:class "bg-white rounded-lg border p-4"}
         [:div {:class "text-sm text-gray-500"} "Errors"]
         [:div {:class "text-2xl font-bold text-red-600"}
          (count (filter #(= "ERROR" (:status %)) spans))]]
        [:div {:class "bg-white rounded-lg border p-4"}
         [:div {:class "text-sm text-gray-500"} "Service"]
         [:div {:class "text-lg font-semibold"} (:service-name (first spans))]]]
       ;; Waterfall chart
       [:div {:class "bg-white rounded-lg shadow-sm border"}
        [:div {:class "px-5 py-4 border-b"}
         [:h2 {:class "text-lg font-semibold text-gray-900"} "Span Waterfall"]]
        [:div {:class "p-4 space-y-1"}
         (for [span spans]
           (waterfall-span span max-duration))]]])))
