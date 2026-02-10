(ns chengis.web.views.secret-rotation
  "Admin views for secret rotation policy management."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- format-timestamp
  "Format a timestamp for display, showing date and time."
  [ts]
  (if ts
    (let [s (str ts)]
      (if (> (count s) 19)
        (subs s 0 19)
        s))
    "\u2014"))

(defn- enabled-badge
  "Render an enabled/disabled badge."
  [enabled]
  (if (and enabled (not (zero? enabled)))
    [:span {:class "inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800 border border-green-200"}
     "Enabled"]
    [:span {:class "inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-600 border border-gray-200"}
     "Disabled"]))

;; ---------------------------------------------------------------------------
;; Policy table
;; ---------------------------------------------------------------------------

(defn- policy-row
  "Render a single row in the rotation policies table."
  [policy csrf-token]
  [:tr {:class "hover:bg-gray-50"}
   [:td {:class "px-4 py-3 text-sm font-medium text-gray-900"} (:secret-name policy)]
   [:td {:class "px-4 py-3 text-sm text-gray-600"}
    [:span {:class "font-mono bg-gray-50 text-gray-700 px-2 py-0.5 rounded text-xs"}
     (or (:secret-scope policy) "global")]]
   [:td {:class "px-4 py-3 text-sm text-right"} (:rotation-interval-days policy)]
   [:td {:class "px-4 py-3 text-sm text-right"} (:max-versions policy)]
   [:td {:class "px-4 py-3 text-sm text-right"} (:notify-days-before policy)]
   [:td {:class "px-4 py-3 text-sm text-gray-500"} (format-timestamp (:last-rotated-at policy))]
   [:td {:class "px-4 py-3 text-sm text-gray-500"} (format-timestamp (:next-rotation-at policy))]
   [:td {:class "px-4 py-3"} (enabled-badge (:enabled policy))]
   [:td {:class "px-4 py-3 text-sm"}
    [:div {:class "flex items-center gap-2"}
     ;; Toggle enabled/disabled
     [:form {:method "POST" :action (str "/admin/rotation/toggle/" (:id policy))}
      (when csrf-token
        [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
      [:button {:type "submit"
                :class "text-blue-600 hover:text-blue-800 text-xs font-medium"}
       (if (and (:enabled policy) (not (zero? (:enabled policy))))
         "Disable"
         "Enable")]]
     ;; Delete
     [:form {:method "POST" :action (str "/admin/rotation/delete/" (:id policy))}
      (when csrf-token
        [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
      [:button {:type "submit"
                :class "text-red-600 hover:text-red-800 text-xs font-medium"
                :onclick "return confirm('Delete this rotation policy?')"}
       "Delete"]]]]])

(defn- policies-table
  "Render the policies table."
  [policies csrf-token]
  (if (empty? policies)
    [:div {:class "px-5 py-8 text-center text-gray-500"}
     "No rotation policies configured yet."]
    [:table {:class "min-w-full divide-y divide-gray-200"}
     [:thead {:class "bg-gray-50"}
      [:tr
       [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Secret Name"]
       [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Scope"]
       [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Interval (days)"]
       [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Max Versions"]
       [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Notify Before"]
       [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Last Rotated"]
       [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Next Rotation"]
       [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Status"]
       [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Actions"]]]
     [:tbody {:class "divide-y divide-gray-200"}
      (for [policy policies]
        (policy-row policy csrf-token))]]))

;; ---------------------------------------------------------------------------
;; Create policy form
;; ---------------------------------------------------------------------------

(defn- create-policy-form
  "Render the create policy form."
  [csrf-token]
  [:form {:method "POST" :action "/admin/rotation" :class "space-y-4"}
   (when csrf-token
     [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
   [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-4"}
    ;; Secret name
    [:div
     [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Secret Name"]
     [:input {:type "text" :name "secret-name" :required true
              :class "w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm text-sm
                      focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
              :placeholder "e.g., API_KEY"}]]
    ;; Scope dropdown
    [:div
     [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Scope"]
     [:select {:name "secret-scope"
               :class "w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm text-sm
                       focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"}
      [:option {:value "global"} "Global"]
      [:option {:value "job"} "Job"]]]
    ;; Interval days
    [:div
     [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Rotation Interval (days)"]
     [:input {:type "number" :name "rotation-interval-days" :value "90" :min "1"
              :class "w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm text-sm
                      focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"}]]]
   [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-4"}
    ;; Max versions
    [:div
     [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Max Versions"]
     [:input {:type "number" :name "max-versions" :value "3" :min "1"
              :class "w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm text-sm
                      focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"}]]
    ;; Notify before
    [:div
     [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Notify Days Before"]
     [:input {:type "number" :name "notify-days-before" :value "7" :min "0"
              :class "w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm text-sm
                      focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"}]]
    ;; Submit
    [:div {:class "flex items-end"}
     [:button {:type "submit"
               :class "w-full bg-blue-600 text-white px-4 py-2 rounded-md text-sm font-medium
                       hover:bg-blue-700 active:bg-blue-800 transition-colors
                       focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"}
      "Create Policy"]]]])

;; ---------------------------------------------------------------------------
;; Version history table
;; ---------------------------------------------------------------------------

(defn- version-row
  "Render a single row in the version history table."
  [version]
  [:tr {:class "hover:bg-gray-50"}
   [:td {:class "px-4 py-3 text-sm font-medium text-gray-900"} (:secret-name version)]
   [:td {:class "px-4 py-3 text-sm text-right font-mono"} (:version version)]
   [:td {:class "px-4 py-3 text-sm text-gray-500"} (format-timestamp (:rotated-at version))]
   [:td {:class "px-4 py-3 text-sm text-gray-600"} (or (:rotated-by version) "\u2014")]
   [:td {:class "px-4 py-3 text-sm text-gray-600"} (or (:rotation-reason version) "\u2014")]])

(defn- versions-table
  "Render the version history table."
  [versions]
  (if (empty? versions)
    [:div {:class "px-5 py-8 text-center text-gray-500"}
     "No rotation history yet."]
    [:table {:class "min-w-full divide-y divide-gray-200"}
     [:thead {:class "bg-gray-50"}
      [:tr
       [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Secret Name"]
       [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Version"]
       [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Rotated At"]
       [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Rotated By"]
       [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Reason"]]]
     [:tbody {:class "divide-y divide-gray-200"}
      (for [v versions]
        (version-row v))]]))

;; ---------------------------------------------------------------------------
;; Main page
;; ---------------------------------------------------------------------------

(defn render-rotation-page
  "Main rotation admin page. Displays policies, create form, and version history."
  [{:keys [policies versions csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title "Secret Rotation" :csrf-token csrf-token
     :user user :auth-enabled auth-enabled}
    [:div {:class "space-y-6"}
     ;; Header
     [:div {:class "flex items-center justify-between"}
      [:h1 {:class "text-2xl font-bold text-gray-900"} "Secret Rotation"]
      [:div {:class "text-sm text-gray-500"}
       "Manage automatic secret rotation policies"]]

     ;; Summary cards
     [:div {:class "grid grid-cols-3 gap-4"}
      [:div {:class "bg-white rounded-lg border p-4"}
       [:div {:class "text-2xl font-bold text-gray-900"} (count policies)]
       [:div {:class "text-sm text-gray-500"} "Total Policies"]]
      [:div {:class "bg-white rounded-lg border p-4"}
       [:div {:class "text-2xl font-bold text-green-600"}
        (count (filter #(and (:enabled %) (not (zero? (:enabled %)))) policies))]
       [:div {:class "text-sm text-gray-500"} "Active Policies"]]
      [:div {:class "bg-white rounded-lg border p-4"}
       [:div {:class "text-2xl font-bold text-blue-600"} (count versions)]
       [:div {:class "text-sm text-gray-500"} "Recent Rotations"]]]

     ;; Create policy form
     [:div {:class "bg-white rounded-lg shadow-sm border"}
      [:div {:class "px-5 py-4 border-b"}
       [:h2 {:class "text-lg font-semibold text-gray-900"} "Create Rotation Policy"]]
      [:div {:class "p-5"}
       (create-policy-form csrf-token)]]

     ;; Policies table
     [:div {:class "bg-white rounded-lg shadow-sm border"}
      [:div {:class "px-5 py-4 border-b"}
       [:h2 {:class "text-lg font-semibold text-gray-900"} "Rotation Policies"]]
      (policies-table policies csrf-token)]

     ;; Version history
     [:div {:class "bg-white rounded-lg shadow-sm border"}
      [:div {:class "px-5 py-4 border-b"}
       [:h2 {:class "text-lg font-semibold text-gray-900"} "Recent Rotation History"]]
      (versions-table versions)]]))
