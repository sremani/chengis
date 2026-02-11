(ns chengis.web.views.releases
  "Release management views — list with status badges, create form, detail page."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- status-badge [status]
  (let [colors (case status
                 "draft" "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"
                 "published" "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"
                 "deprecated" "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
                 "bg-gray-100 text-gray-800")]
    [:span {:class (str "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium " colors)}
     (escape-html (str status))]))

(defn- release-row [r csrf-token]
  [:tr {:class "border-b border-gray-100 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-800"}
   [:td {:class "py-3 px-4 text-sm font-mono"}
    [:a {:href (str "/deploy/releases/" (:id r))
         :class "text-blue-600 hover:underline dark:text-blue-400"}
     (escape-html (str (:version r)))]]
   [:td {:class "py-3 px-4 text-sm"} (escape-html (str (:title r)))]
   [:td {:class "py-3 px-4 text-sm"} (escape-html (str (:job-id r)))]
   [:td {:class "py-3 px-4"} (status-badge (:status r))]
   [:td {:class "py-3 px-4 text-sm text-gray-500 dark:text-gray-400"}
    (escape-html (str (:created-at r)))]
   [:td {:class "py-3 px-4 text-sm"}
    (when (= "draft" (:status r))
      [:form {:method "POST" :action (str "/deploy/releases/" (:id r) "/publish") :class "inline"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:button {:type "submit" :class "text-green-600 hover:text-green-800 dark:text-green-400 text-xs"}
        "Publish"]])
    (when (= "published" (:status r))
      [:form {:method "POST" :action (str "/deploy/releases/" (:id r) "/deprecate") :class "inline"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:button {:type "submit" :class "text-yellow-600 hover:text-yellow-800 dark:text-yellow-400 text-xs"}
        "Deprecate"]])]])

(defn render
  "Render the releases list page."
  [{:keys [releases builds csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title "Releases" :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       [:div {:class "flex justify-between items-center mb-6"}
        [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"} "Releases"]
        [:span {:class "text-sm text-gray-500 dark:text-gray-400"}
         (str (count releases) " release(s)")]]
       ;; Create form
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6 mb-6"}
        [:h2 {:class "text-lg font-semibold mb-4 text-gray-900 dark:text-white"} "Create Release"]
        [:form {:method "POST" :action "/deploy/releases"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
         [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-4"}
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Build ID"]
           [:select {:name "build_id" :required true
                     :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}
            [:option {:value ""} "Select a build..."]
            (for [b (or builds [])]
              [:option {:value (:id b)} (str (:id b) " (" (:job-id b) ")")])]]
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Version"]
           [:input {:type "text" :name "version" :placeholder "1.0.0 (auto if empty)"
                    :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}]]
          [:div
           [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Title"]
           [:input {:type "text" :name "title"
                    :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}]]]
         [:div {:class "mt-4"}
          [:label {:class "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Notes"]
          [:textarea {:name "notes" :rows "2"
                      :class "mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm"}]]
         [:button {:type "submit"
                   :class "mt-4 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 text-sm font-medium"}
          "Create Release"]]]
       ;; Releases table
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow overflow-hidden"}
        [:table {:class "min-w-full divide-y divide-gray-200 dark:divide-gray-700"}
         [:thead {:class "bg-gray-50 dark:bg-gray-900"}
          [:tr
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Version"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Title"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Job"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Status"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Created"]
           [:th {:class "py-3 px-4 text-left text-xs font-medium text-gray-500 uppercase"} "Actions"]]]
         [:tbody
          (if (seq releases)
            (for [r releases] (release-row r csrf-token))
            [:tr [:td {:colspan "6" :class "py-8 text-center text-gray-500 dark:text-gray-400"} "No releases yet"]])]]]]))))

(defn render-detail
  "Render a single release detail page."
  [{:keys [release csrf-token user]}]
  (str (h/html
    (layout/base-layout
      {:title (str "Release " (:version release)) :csrf-token csrf-token :user user}
      [:div {:class "container mx-auto px-4 py-8"}
       [:div {:class "flex justify-between items-center mb-6"}
        [:div
         [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-white"}
          (escape-html (str "Release " (:version release)))]
         [:p {:class "text-sm text-gray-500 dark:text-gray-400"}
          "Job: " (escape-html (str (:job-id release)))
          " · Build: " (escape-html (str (:build-id release)))]]
        (status-badge (:status release))]
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow p-6"}
        (when (:title release)
          [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-white mb-2"}
           (escape-html (str (:title release)))])
        (when (:notes release)
          [:p {:class "text-gray-600 dark:text-gray-300 mb-4"} (escape-html (str (:notes release)))])
        [:div {:class "grid grid-cols-2 gap-4 text-sm"}
         [:div [:span {:class "text-gray-500"} "Created by: "] (escape-html (str (:created-by release)))]
         [:div [:span {:class "text-gray-500"} "Created: "] (escape-html (str (:created-at release)))]
         (when (:published-at release)
           [:div [:span {:class "text-gray-500"} "Published: "] (escape-html (str (:published-at release)))])
         (when (:deprecated-at release)
           [:div [:span {:class "text-gray-500"} "Deprecated: "] (escape-html (str (:deprecated-at release)))])]
        [:div {:class "mt-4 flex gap-2"}
         (when (= "draft" (:status release))
           [:form {:method "POST" :action (str "/deploy/releases/" (:id release) "/publish")}
            [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
            [:button {:type "submit" :class "px-3 py-1 bg-green-600 text-white rounded text-sm hover:bg-green-700"}
             "Publish"]])
         (when (= "published" (:status release))
           [:form {:method "POST" :action (str "/deploy/releases/" (:id release) "/deprecate")}
            [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
            [:button {:type "submit" :class "px-3 py-1 bg-yellow-600 text-white rounded text-sm hover:bg-yellow-700"}
             "Deprecate"]])]]]))))
