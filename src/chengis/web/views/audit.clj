(ns chengis.web.views.audit
  "Audit log viewer — admin-only view."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- format-detail [detail]
  (when detail
    [:pre {:class "text-xs text-gray-500 mt-1 whitespace-pre-wrap"}
     (escape-html (pr-str detail))]))

(defn render
  "Render the audit log page."
  [{:keys [audits total-count page page-size filters csrf-token user auth-enabled]}]
  (let [page (or page 1)
        page-size (or page-size 50)
        total-pages (max 1 (int (Math/ceil (/ (or total-count 0) page-size))))]
    (layout/base-layout
      {:title "Audit Log" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
      [:div {:class "space-y-6"}
       [:div {:class "flex items-center justify-between"}
        [:h1 {:class "text-2xl font-bold text-gray-900"} "Audit Log"]
        [:span {:class "text-sm text-gray-500"} (str total-count " total events")]]

       ;; Filter form
       [:form {:method "GET" :action "/admin/audit" :class "flex gap-4 items-end"}
        [:div
         [:label {:class "block text-xs font-medium text-gray-600 mb-1"} "Action"]
         [:input {:type "text" :name "action" :value (get filters :action "")
                  :placeholder "e.g. login, trigger-build"
                  :class "px-2 py-1 border border-gray-300 rounded text-sm w-40"}]]
        [:div
         [:label {:class "block text-xs font-medium text-gray-600 mb-1"} "User"]
         [:input {:type "text" :name "username" :value (get filters :username "")
                  :placeholder "username"
                  :class "px-2 py-1 border border-gray-300 rounded text-sm w-32"}]]
        [:button {:type "submit"
                  :class "px-3 py-1 bg-blue-600 text-white rounded text-sm hover:bg-blue-700"}
         "Filter"]]

       ;; Table
       [:div {:class "bg-white rounded-lg shadow overflow-hidden"}
        [:table {:class "min-w-full divide-y divide-gray-200"}
         [:thead {:class "bg-gray-50"}
          [:tr
           [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Time"]
           [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "User"]
           [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Action"]
           [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Resource"]
           [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "IP"]]]
         [:tbody {:class "bg-white divide-y divide-gray-200"}
          (if (seq audits)
            (for [audit audits]
              [:tr {:class "hover:bg-gray-50"}
               [:td {:class "px-4 py-2 text-sm text-gray-600 whitespace-nowrap"}
                (or (:timestamp audit) "—")]
               [:td {:class "px-4 py-2 text-sm font-medium text-gray-900"}
                (or (:username audit) "system")]
               [:td {:class "px-4 py-2 text-sm"}
                [:span {:class "inline-block px-2 py-0.5 bg-blue-100 text-blue-800 rounded text-xs font-medium"}
                 (or (:action audit) "—")]]
               [:td {:class "px-4 py-2 text-sm text-gray-600"}
                (when (:resource-type audit)
                  [:span (str (:resource-type audit)
                              (when (:resource-id audit)
                                (str "/" (:resource-id audit))))])
                (format-detail (:detail audit))]
               [:td {:class "px-4 py-2 text-sm text-gray-400"}
                (or (:ip-address audit) "—")]])
            [:tr
             [:td {:colspan 5 :class "px-4 py-8 text-center text-gray-400"}
              "No audit events found"]])]]]

       ;; Pagination
       (when (> total-pages 1)
         [:div {:class "flex items-center justify-between mt-4"}
          [:span {:class "text-sm text-gray-500"}
           (str "Page " page " of " total-pages)]
          [:div {:class "flex gap-2"}
           (when (> page 1)
             [:a {:href (str "/admin/audit?page=" (dec page))
                  :class "px-3 py-1 bg-gray-200 rounded text-sm hover:bg-gray-300"}
              "Previous"])
           (when (< page total-pages)
             [:a {:href (str "/admin/audit?page=" (inc page))
                  :class "px-3 py-1 bg-gray-200 rounded text-sm hover:bg-gray-300"}
              "Next"])]])])))
