(ns chengis.web.views.log-search
  "Build log search page views."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [hiccup.util :refer [raw-string]]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- status-options
  "Status filter options for the dropdown."
  [selected]
  (for [[val label] [["" "All Statuses"]
                      ["success" "Success"]
                      ["failure" "Failure"]
                      ["aborted" "Aborted"]
                      ["running" "Running"]
                      ["queued" "Queued"]]]
    [:option (cond-> {:value val}
               (= val (or selected "")) (assoc :selected "selected"))
     label]))

(defn- job-options
  "Job filter options for the dropdown."
  [jobs selected]
  (cons
    [:option {:value "" :selected (when (str/blank? selected) "selected")} "All Jobs"]
    (for [job jobs]
      [:option (cond-> {:value (:name job)}
                 (= (:name job) selected) (assoc :selected "selected"))
       (:name job)])))

;; ---------------------------------------------------------------------------
;; Result rendering
;; ---------------------------------------------------------------------------

(defn- render-matching-line
  "Render a single matching line with line number and highlight."
  [line]
  (let [is-match (:is-match line)
        bg (if is-match "bg-yellow-50 dark:bg-yellow-900/30" "bg-gray-50 dark:bg-gray-800")
        text-color (if is-match "text-gray-900 dark:text-gray-100" "text-gray-400 dark:text-gray-500")]
    [:div {:class (str "flex font-mono text-xs " bg)}
     [:span {:class "select-none text-gray-400 w-12 text-right pr-2 py-0.5 border-r border-gray-200 flex-shrink-0"}
      (:line-number line)]
     [:span {:class "pl-1 text-xs text-gray-400 w-14 flex-shrink-0 py-0.5"}
      (when (:source line)
        [:span {:class (if (= "stderr" (:source line))
                         "text-red-400"
                         "text-blue-400")}
         (:source line)])]
     [:pre {:class (str "pl-2 py-0.5 overflow-x-auto whitespace-pre flex-1 " text-color)}
      (if is-match
        (raw-string (:highlighted line))
        (:text line))]]))

(defn- render-result-card
  "Render a single search result card."
  [result]
  [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 mb-4"}
   ;; Header: build link, job, stage/step
   [:div {:class "px-4 py-3 border-b dark:border-gray-700 bg-gray-50 dark:bg-gray-800/50 flex items-center justify-between flex-wrap gap-2"}
    [:div {:class "flex items-center gap-3"}
     [:a {:href (str "/builds/" (:build-id result))
          :class "text-blue-600 hover:text-blue-800 hover:underline font-mono text-sm font-medium"}
      (str "#" (:build-number result))]
     [:span {:class "text-gray-500 text-sm"} (:job-name result)]
     [:span {:class "text-gray-400 text-xs"} "/"]
     [:span {:class "text-gray-600 text-sm"} (:stage-name result)]
     [:span {:class "text-gray-400 text-xs"} "/"]
     [:span {:class "text-gray-600 text-sm"} (:step-name result)]]
    [:div {:class "flex items-center gap-2"}
     (when (:status result)
       (c/status-badge (:status result)))
     (when (:started-at result)
       [:span {:class "text-gray-400 text-xs font-mono"} (:started-at result)])]]
   ;; Matching lines
   [:div {:class "divide-y divide-gray-100 overflow-hidden rounded-b-lg"}
    (for [line (:matching-lines result)]
      (render-matching-line line))]])

;; ---------------------------------------------------------------------------
;; Pagination
;; ---------------------------------------------------------------------------

(defn- render-pagination
  "Render pagination controls."
  [{:keys [total-count page per-page query job-filter status-filter]}]
  (let [total-pages (max 1 (int (Math/ceil (/ (double total-count) (double per-page)))))
        has-prev (> page 1)
        has-next (< page total-pages)]
    (when (> total-pages 1)
      [:div {:class "flex items-center justify-between mt-6"}
       [:div {:class "text-sm text-gray-500"}
        (str "Page " page " of " total-pages " (" total-count " matching steps)")]
       [:div {:class "flex gap-2"}
        (if has-prev
          [:button {:hx-post "/search/logs"
                    :hx-target "#results"
                    :hx-include "[name='q'],[name='job'],[name='status']"
                    :hx-vals (str "{\"page\":\"" (dec page) "\"}")
                    :class "px-3 py-1.5 rounded-lg text-sm font-medium bg-gray-100 text-gray-700 hover:bg-gray-200"}
           "Previous"]
          [:span {:class "px-3 py-1.5 rounded-lg text-sm font-medium bg-gray-50 text-gray-300"} "Previous"])
        (if has-next
          [:button {:hx-post "/search/logs"
                    :hx-target "#results"
                    :hx-include "[name='q'],[name='job'],[name='status']"
                    :hx-vals (str "{\"page\":\"" (inc page) "\"}")
                    :class "px-3 py-1.5 rounded-lg text-sm font-medium bg-gray-100 text-gray-700 hover:bg-gray-200"}
           "Next"]
          [:span {:class "px-3 py-1.5 rounded-lg text-sm font-medium bg-gray-50 text-gray-300"} "Next"])]])))

;; ---------------------------------------------------------------------------
;; Public views
;; ---------------------------------------------------------------------------

(defn render-search-results
  "Render search results fragment (for htmx partial updates).
   {:keys [results query total-count page per-page job-filter status-filter]}"
  [{:keys [results query total-count page per-page job-filter status-filter]}]
  (let [page (or page 1)
        per-page (or per-page 20)]
    [:div
     ;; Results summary
     (when query
       [:div {:class "mb-4 text-sm text-gray-500"}
        (if (pos? total-count)
          (str total-count " matching step" (when (not= 1 total-count) "s")
               " found for \"" query "\"")
          (str "No results found for \"" query "\""))])
     ;; Result cards
     (if (seq results)
       [:div
        (for [result results]
          (render-result-card result))
        (render-pagination {:total-count total-count
                            :page page
                            :per-page per-page
                            :query query
                            :job-filter job-filter
                            :status-filter status-filter})]
       (when query
         [:div {:class "text-center py-12 text-gray-400 dark:text-gray-500"}
          [:p {:class "text-lg"} "No matching logs found."]
          [:p {:class "text-sm mt-1"} "Try a different search term or adjust filters."]]))]))

(defn render-log-search-page
  "Log search page with search form and results.
   {:keys [csrf-token user auth-enabled query results total-count
           job-filter status-filter jobs page]}"
  [{:keys [csrf-token user auth-enabled query results total-count
           job-filter status-filter jobs page notifications-enabled]}]
  (let [page (or page 1)
        per-page 20]
    (layout/base-layout
      {:title "Search Logs" :csrf-token csrf-token
       :user user :auth-enabled auth-enabled
       :notifications-enabled notifications-enabled}
      [:div {:class "space-y-6"}
       ;; Page header
       [:h1 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Search Build Logs"]

       ;; Search form
       [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 p-5"}
        [:form {:hx-post "/search/logs"
                :hx-target "#results"
                :hx-indicator "#search-spinner"
                :class "space-y-4"}
         ;; Search input row
         [:div {:class "flex gap-3"}
          [:div {:class "flex-1"}
           [:input {:type "text"
                    :name "q"
                    :value (or query "")
                    :placeholder "Search build logs..."
                    :class "w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm
                            focus:ring-2 focus:ring-blue-500 focus:border-blue-500
                            placeholder-gray-400"
                    :autofocus true}]]
          [:button {:type "submit"
                    :class "px-6 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-medium
                            hover:bg-blue-700 active:bg-blue-800 transition-colors
                            focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"}
           "Search"]]
         ;; Filter row
         [:div {:class "flex gap-3"}
          [:select {:name "job"
                    :class "px-3 py-2 border border-gray-300 rounded-lg text-sm bg-white
                            focus:ring-2 focus:ring-blue-500 focus:border-blue-500"}
           (job-options jobs job-filter)]
          [:select {:name "status"
                    :class "px-3 py-2 border border-gray-300 rounded-lg text-sm bg-white
                            focus:ring-2 focus:ring-blue-500 focus:border-blue-500"}
           (status-options status-filter)]]
         ;; Loading indicator
         [:div {:id "search-spinner" :class "htmx-indicator text-sm text-gray-500"}
          "Searching..."]]]

       ;; Results area (htmx target)
       [:div {:id "results"}
        (render-search-results {:results results
                                :query query
                                :total-count (or total-count 0)
                                :page page
                                :per-page per-page
                                :job-filter job-filter
                                :status-filter status-filter})]])))
