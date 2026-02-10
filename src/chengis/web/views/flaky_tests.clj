(ns chengis.web.views.flaky-tests
  "Flaky test detection dashboard views."
  (:require [chengis.web.views.layout :as layout]))

(defn- flakiness-color
  "CSS color class based on flakiness score."
  [score]
  (cond
    (nil? score) "text-gray-500"
    (< score 0.3) "text-yellow-600"
    (< score 0.6) "text-orange-600"
    :else          "text-red-600"))

(defn- flaky-test-row
  "Render a single row in the flaky tests table."
  [test-entry]
  [:tr {:class "hover:bg-gray-50"}
   [:td {:class "px-4 py-3 text-sm font-medium"} (:test-name test-entry)]
   [:td {:class "px-4 py-3 text-sm text-gray-500"} (or (:test-suite test-entry) "—")]
   [:td {:class "px-4 py-3 text-sm text-gray-500"} (:job-id test-entry)]
   [:td {:class (str "px-4 py-3 text-sm text-right font-bold " (flakiness-color (:flakiness-score test-entry)))}
    (when (:flakiness-score test-entry)
      (format "%.2f" (double (:flakiness-score test-entry))))]
   [:td {:class "px-4 py-3 text-sm text-right"} (or (:total-runs test-entry) 0)]
   [:td {:class "px-4 py-3 text-sm text-right text-green-600"} (or (:pass-count test-entry) 0)]
   [:td {:class "px-4 py-3 text-sm text-right text-red-600"} (or (:fail-count test-entry) 0)]
   [:td {:class "px-4 py-3 text-sm text-gray-400"}
    (when-let [last-seen (:last-seen-at test-entry)]
      (subs (str last-seen) 0 (min 10 (count (str last-seen)))))]])

(defn flaky-tests-page
  "Render the flaky test detection dashboard."
  [{:keys [flaky-tests csrf-token]}]
  (layout/base-layout
    {:title "Flaky Tests" :csrf-token csrf-token}
    [:div {:class "space-y-6"}
     [:div {:class "flex items-center justify-between"}
      [:h1 {:class "text-2xl font-bold text-gray-900"} "Flaky Test Detection"]
      [:span {:class "text-sm text-gray-500"} (str (count flaky-tests) " flaky tests detected")]]

     (if (empty? flaky-tests)
       [:div {:class "bg-green-50 border border-green-200 rounded-lg p-6 text-center"}
        [:p {:class "text-green-800 font-medium"} "No flaky tests detected."]
        [:p {:class "text-green-600 text-sm mt-1"}
         "Tests are analyzed after builds complete. Flaky tests will appear here when detected."]]
       [:div {:class "bg-white rounded-lg shadow-sm border"}
        [:div {:class "px-5 py-4 border-b"}
         [:h2 {:class "text-lg font-semibold text-gray-900"} "Flaky Tests"]]
        [:table {:class "min-w-full divide-y divide-gray-200"}
         [:thead {:class "bg-gray-50"}
          [:tr
           [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Test"]
           [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Suite"]
           [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Job"]
           [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Flakiness"]
           [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Runs"]
           [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Pass"]
           [:th {:class "px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase"} "Fail"]
           [:th {:class "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase"} "Last Seen"]]]
         [:tbody {:class "divide-y divide-gray-200"}
          (for [t flaky-tests]
            (flaky-test-row t))]]])]))
