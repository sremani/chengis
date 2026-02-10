(ns chengis.web.views.webhook-replay
  "Hiccup views for webhook replay functionality."
  (:require [hiccup2.core :as h]))

;; ---------------------------------------------------------------------------
;; Replayable events panel
;; ---------------------------------------------------------------------------

(defn replayable-events-panel
  "Render the list of replayable webhook events."
  [{:keys [events csrf-token]}]
  [:div {:class "bg-white rounded-lg shadow p-6"}
   [:h3 {:class "text-lg font-semibold mb-4"} "Webhook Replay"]
   [:p {:class "text-sm text-gray-500 mb-4"}
    "Re-deliver webhook events from stored payloads. Only events with stored payloads are shown."]

   (if (empty? events)
     [:p {:class "text-gray-400 text-sm italic"} "No replayable events found."]
     [:table {:class "w-full text-sm"}
      [:thead
       [:tr {:class "border-b text-left text-gray-500"}
        [:th {:class "py-2 pr-4"} "Event ID"]
        [:th {:class "py-2 pr-4"} "Provider"]
        [:th {:class "py-2 pr-4"} "Event Type"]
        [:th {:class "py-2 pr-4"} "Repo"]
        [:th {:class "py-2 pr-4"} "Replay Count"]
        [:th {:class "py-2 pr-4"} "Status"]
        [:th {:class "py-2"} ""]]]
      [:tbody
       (for [event events]
         [:tr {:class "border-b hover:bg-gray-50"}
          [:td {:class "py-2 pr-4 font-mono text-xs"}
           (subs (:id event) 0 (min 12 (count (:id event))))]
          [:td {:class "py-2 pr-4 text-xs"} (name (or (:provider event) :unknown))]
          [:td {:class "py-2 pr-4 text-xs"} (:event-type event)]
          [:td {:class "py-2 pr-4 text-xs text-gray-500"} (:repo-url event)]
          [:td {:class "py-2 pr-4 text-xs text-center"}
           (or (:replay-count event) 0)]
          [:td {:class "py-2 pr-4"}
           (let [status (or (:status event) "unknown")
                 colors (case (name status)
                          "processed" "bg-green-100 text-green-800"
                          "error" "bg-red-100 text-red-800"
                          "bg-gray-100 text-gray-600")]
             [:span {:class (str "inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium " colors)}
              (name status)])]
          [:td {:class "py-2"}
           [:form {:method "POST"
                   :action (str "/api/webhooks/" (:id event) "/replay")
                   :class "inline"}
            (when csrf-token
              [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
            [:button {:type "submit"
                      :class "bg-yellow-500 text-white px-3 py-1 rounded text-xs hover:bg-yellow-600"}
             "Replay"]]]])]])])
