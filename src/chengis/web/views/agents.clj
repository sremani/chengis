(ns chengis.web.views.agents
  "Agent management page — shows registered agents, their status, health,
   circuit breaker state, queue depth, and system resource utilization."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- status-badge [status]
  (case (keyword status)
    :online  [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800"}
              "Online"]
    :offline [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800"}
              "Offline"]
    :draining [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-100 text-yellow-800"}
               "Draining"]
    [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-800"}
     (str (name status))]))

(defn- cb-badge
  "Circuit breaker state badge for an agent."
  [cb-state]
  (case (:state cb-state)
    :open     [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-red-100 text-red-800"
                      :title (str "Failures: " (:failures cb-state))}
               "CB Open"]
    :half-open [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-yellow-100 text-yellow-800"
                       :title "Probing — next request is a test"}
                "CB Half-Open"]
    ;; :closed or nil — show green only if there have been recent failures
    (when (and cb-state (pos? (or (:failures cb-state) 0)))
      [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800"
              :title (str "Failures: " (:failures cb-state))}
       "CB Closed"])))

(defn- format-heartbeat-age [age-ms]
  (cond
    (nil? age-ms) "Never"
    (< age-ms 60000) (str (int (/ age-ms 1000)) "s ago")
    (< age-ms 3600000) (str (int (/ age-ms 60000)) "m ago")
    :else (str (int (/ age-ms 3600000)) "h ago")))

(defn- label-badges [labels]
  (when (seq labels)
    [:div {:class "flex flex-wrap gap-1"}
     (for [label labels]
       [:span {:class "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-blue-100 text-blue-800"}
        (escape-html (str label))])]))

(defn- system-info-row
  "Render a compact system resource line for an agent."
  [system-info]
  (when system-info
    (let [{:keys [heap-used-mb heap-max-mb cpu-load disk-free-mb]} system-info]
      [:div {:class "text-xs text-gray-400 mt-1"}
       (when (and heap-used-mb heap-max-mb)
         [:span {:class "mr-3"
                 :title "JVM Heap Usage"}
          (str "Heap: " heap-used-mb "/" heap-max-mb "MB")])
       (when cpu-load
         [:span {:class "mr-3"
                 :title "System Load Average"}
          (str "CPU: " (format "%.1f" (double cpu-load)))])
       (when disk-free-mb
         [:span {:title "Free Disk Space"}
          (str "Disk: " (if (> disk-free-mb 1024)
                          (str (int (/ disk-free-mb 1024)) "GB")
                          (str disk-free-mb "MB"))
               " free")])])))

(defn- utilization-bar
  "Render a small progress bar showing build utilization."
  [current max-builds]
  (let [pct (if (and max-builds (pos? max-builds))
              (min 100 (int (* 100 (/ (or current 0) max-builds))))
              0)
        color (cond
                (>= pct 90) "bg-red-500"
                (>= pct 60) "bg-yellow-500"
                :else "bg-green-500")]
    [:div {:class "flex items-center gap-2"}
     [:div {:class "w-16 bg-gray-200 rounded-full h-2"}
      [:div {:class (str color " h-2 rounded-full")
             :style (str "width: " pct "%")}]]
     [:span {:class "text-sm text-gray-500"}
      (str (or current 0) "/" (or max-builds 2))]]))

(defn render
  "Render the agents management page."
  [{:keys [agents summary circuit-breakers queue-depth csrf-token]}]
  (layout/base-layout
    {:title "Agents" :csrf-token csrf-token}
    ;; Summary cards
    [:div {:class "grid grid-cols-1 md:grid-cols-5 gap-4 mb-8"}
     [:div {:class "bg-white rounded-lg shadow p-4"}
      [:p {:class "text-sm text-gray-500"} "Total Agents"]
      [:p {:class "text-3xl font-bold text-gray-900"} (str (:total summary 0))]]
     [:div {:class "bg-white rounded-lg shadow p-4"}
      [:p {:class "text-sm text-gray-500"} "Online"]
      [:p {:class "text-3xl font-bold text-green-600"} (str (:online summary 0))]]
     [:div {:class "bg-white rounded-lg shadow p-4"}
      [:p {:class "text-sm text-gray-500"} "Offline"]
      [:p {:class "text-3xl font-bold text-red-600"} (str (:offline summary 0))]]
     [:div {:class "bg-white rounded-lg shadow p-4"}
      [:p {:class "text-sm text-gray-500"} "Active / Capacity"]
      [:p {:class "text-3xl font-bold text-blue-600"}
       (str (:total-active summary 0) " / " (:total-capacity summary 0))]]
     (when (some? queue-depth)
       [:div {:class "bg-white rounded-lg shadow p-4"}
        [:p {:class "text-sm text-gray-500"} "Queue Depth"]
        [:p {:class (str "text-3xl font-bold "
                         (if (pos? (or queue-depth 0)) "text-orange-600" "text-gray-900"))}
         (str (or queue-depth 0))]])]

    ;; Circuit breaker warning banner
    (let [open-count (count (filter #(= :open (:state (val %))) circuit-breakers))]
      (when (pos? open-count)
        [:div {:class "mb-6 bg-red-50 border border-red-200 rounded-lg p-4"}
         [:div {:class "flex items-center"}
          [:span {:class "text-red-600 font-medium"}
           (str open-count " agent(s) have open circuit breakers — builds will not be dispatched to them until they recover.")]]]))

    ;; Agents table
    [:div {:class "bg-white rounded-lg shadow overflow-hidden"}
     [:div {:class "px-6 py-4 border-b"}
      [:h2 {:class "text-lg font-semibold text-gray-900"} "Registered Agents"]]
     (if (empty? agents)
       [:div {:class "px-6 py-8 text-center text-gray-500"}
        [:p "No agents registered."]
        [:p {:class "text-sm mt-2"} "Start an agent with: "
         [:code {:class "bg-gray-100 px-2 py-1 rounded text-sm"}
          "lein run agent --master-url http://localhost:8080"]]]
       [:table {:class "min-w-full divide-y divide-gray-200"}
        [:thead {:class "bg-gray-50"}
         [:tr
          [:th {:class "px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Name"]
          [:th {:class "px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Status"]
          [:th {:class "px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "URL"]
          [:th {:class "px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Labels"]
          [:th {:class "px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Builds"]
          [:th {:class "px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Circuit"]
          [:th {:class "px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Heartbeat"]]]
        [:tbody {:class "bg-white divide-y divide-gray-200"}
         (for [agent agents]
           (let [agent-cb (get circuit-breakers (str (:id agent)))]
             [:tr
              [:td {:class "px-6 py-4 whitespace-nowrap"}
               [:div {:class "text-sm font-medium text-gray-900"}
                (escape-html (str (:name agent)))]
               (system-info-row (:system-info agent))]
              [:td {:class "px-6 py-4 whitespace-nowrap"}
               (status-badge (:status agent))]
              [:td {:class "px-6 py-4 whitespace-nowrap text-sm text-gray-500"}
               (escape-html (str (:url agent)))]
              [:td {:class "px-6 py-4"}
               (label-badges (:labels agent))]
              [:td {:class "px-6 py-4 whitespace-nowrap"}
               (utilization-bar (:current-builds agent) (:max-builds agent))]
              [:td {:class "px-6 py-4 whitespace-nowrap"}
               (cb-badge agent-cb)]
              [:td {:class "px-6 py-4 whitespace-nowrap text-sm text-gray-500"}
               (format-heartbeat-age (:heartbeat-age-ms agent))]]))]])]))
