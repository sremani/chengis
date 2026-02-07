(ns chengis.web.views.agents
  "Agent management page — shows registered agents, their status, and health."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- status-badge [status]
  (case (keyword status)
    :online  [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800"}
              "● Online"]
    :offline [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800"}
              "● Offline"]
    [:span {:class "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-800"}
     (str "● " (name status))]))

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

(defn render
  "Render the agents management page."
  [{:keys [agents summary csrf-token]}]
  (layout/base-layout
    {:title "Agents" :csrf-token csrf-token}
    ;; Summary cards
    [:div {:class "grid grid-cols-1 md:grid-cols-4 gap-4 mb-8"}
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
       (str (:total-active summary 0) " / " (:total-capacity summary 0))]]]

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
          [:th {:class "px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Heartbeat"]]]
        [:tbody {:class "bg-white divide-y divide-gray-200"}
         (for [agent agents]
           [:tr
            [:td {:class "px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900"}
             (escape-html (str (:name agent)))]
            [:td {:class "px-6 py-4 whitespace-nowrap"}
             (status-badge (:status agent))]
            [:td {:class "px-6 py-4 whitespace-nowrap text-sm text-gray-500"}
             (escape-html (str (:url agent)))]
            [:td {:class "px-6 py-4"}
             (label-badges (:labels agent))]
            [:td {:class "px-6 py-4 whitespace-nowrap text-sm text-gray-500"}
             (str (:current-builds agent 0) " / " (:max-builds agent 2))]
            [:td {:class "px-6 py-4 whitespace-nowrap text-sm text-gray-500"}
             (format-heartbeat-age (:heartbeat-age-ms agent))]])]])]))
