(ns chengis.web.views.webhooks
  "Admin webhook event history page."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]))

(defn- provider-badge
  "Colored badge for webhook provider."
  [provider]
  (let [colors (case provider
                 "github" "bg-gray-900 text-white"
                 "gitlab" "bg-orange-600 text-white"
                 "bg-gray-400 text-white")]
    [:span {:class (str "px-2 py-0.5 text-xs font-medium rounded " colors)}
     provider]))

(defn- status-badge
  "Colored badge for event status."
  [status]
  (let [colors (case status
                 "processed" "bg-green-100 text-green-800"
                 "rejected"  "bg-red-100 text-red-800"
                 "error"     "bg-yellow-100 text-yellow-800"
                 "bg-gray-100 text-gray-800")]
    [:span {:class (str "px-2 py-0.5 text-xs font-medium rounded " colors)}
     status]))

(defn- event-row
  "Single webhook event row."
  [event]
  [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
   [:td {:class "py-2 px-3"}
    (provider-badge (:provider event))]
   [:td {:class "py-2 px-3 text-sm font-mono text-gray-600"}
    (or (:event-type event) "-")]
   [:td {:class "py-2 px-3 text-sm"}
    (or (:repo-name event) "-")]
   [:td {:class "py-2 px-3 text-sm text-gray-600"}
    (or (:branch event) "-")]
   [:td {:class "py-2 px-3"}
    (status-badge (:status event))]
   [:td {:class "py-2 px-3 text-sm text-center"}
    (:triggered-builds event)]
   [:td {:class "py-2 px-3 text-sm text-gray-500"}
    (when (:processing-ms event)
      (str (:processing-ms event) "ms"))]
   [:td {:class "py-2 px-3 text-xs text-gray-400"}
    (:created-at event)]])

(defn render
  "Webhook event history page."
  [{:keys [events total page page-size csrf-token user auth-enabled]}]
  (let [total-pages (max 1 (int (Math/ceil (/ (double total) page-size))))]
    (layout/base-layout
      {:title "Webhook Events" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
      (c/page-header "Webhook Events")

      [:div {:class "bg-white rounded-lg shadow-sm border overflow-hidden"}
       [:div {:class "px-5 py-3 border-b bg-gray-50 flex items-center justify-between"}
        [:span {:class "text-sm text-gray-600"}
         (str total " events total")]
        [:span {:class "text-sm text-gray-500"}
         (str "Page " page " of " total-pages)]]

       (if (empty? events)
         [:div {:class "p-8 text-center text-gray-500"}
          "No webhook events recorded yet."]
         [:table {:class "w-full text-sm"}
          [:thead
           [:tr {:class "text-left text-gray-500 border-b bg-gray-50"}
            [:th {:class "py-2 px-3 font-medium"} "Provider"]
            [:th {:class "py-2 px-3 font-medium"} "Event"]
            [:th {:class "py-2 px-3 font-medium"} "Repository"]
            [:th {:class "py-2 px-3 font-medium"} "Branch"]
            [:th {:class "py-2 px-3 font-medium"} "Status"]
            [:th {:class "py-2 px-3 font-medium text-center"} "Triggered"]
            [:th {:class "py-2 px-3 font-medium"} "Duration"]
            [:th {:class "py-2 px-3 font-medium"} "Time"]]]
          [:tbody
           (for [event events]
             (event-row event))]])

       ;; Pagination
       (when (> total-pages 1)
         [:div {:class "px-5 py-3 border-t bg-gray-50 flex items-center justify-center gap-2"}
          (when (> page 1)
            [:a {:href (str "/admin/webhooks?page=" (dec page))
                 :class "px-3 py-1 text-sm bg-white border rounded hover:bg-gray-100"}
             "Previous"])
          (for [p (range 1 (inc (min total-pages 10)))]
            [:a {:href (str "/admin/webhooks?page=" p)
                 :class (str "px-3 py-1 text-sm rounded border "
                             (if (= p page)
                               "bg-blue-600 text-white border-blue-600"
                               "bg-white hover:bg-gray-100"))}
             (str p)])
          (when (< page total-pages)
            [:a {:href (str "/admin/webhooks?page=" (inc page))
                 :class "px-3 py-1 text-sm bg-white border rounded hover:bg-gray-100"}
             "Next"])])])))
