(ns chengis.web.views.linter
  "Web UI views for the pipeline linter."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]))

(defn- error-item
  "Render a single error item."
  [{:keys [location message rule]}]
  [:div {:class "flex items-start gap-3 p-3 bg-red-50 border border-red-200 rounded-lg"}
   [:div {:class "flex-shrink-0 mt-0.5"}
    [:span {:class "inline-flex items-center justify-center w-5 h-5 rounded-full bg-red-100 text-red-600 text-xs font-bold"} "!"]]
   [:div
    [:div {:class "text-sm font-medium text-red-800"} (escape-html location)]
    [:div {:class "text-sm text-red-700 mt-0.5"} (escape-html message)]
    [:div {:class "text-xs text-red-400 mt-1 font-mono"} (str rule)]]])

(defn- warning-item
  "Render a single warning item."
  [{:keys [location message rule]}]
  [:div {:class "flex items-start gap-3 p-3 bg-yellow-50 border border-yellow-200 rounded-lg"}
   [:div {:class "flex-shrink-0 mt-0.5"}
    [:span {:class "inline-flex items-center justify-center w-5 h-5 rounded-full bg-yellow-100 text-yellow-600 text-xs font-bold"} "?"]]
   [:div
    [:div {:class "text-sm font-medium text-yellow-800"} (escape-html location)]
    [:div {:class "text-sm text-yellow-700 mt-0.5"} (escape-html message)]
    [:div {:class "text-xs text-yellow-400 mt-1 font-mono"} (str rule)]]])

(defn- info-badge
  "Render a small info badge."
  [label value]
  [:span {:class "inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium bg-blue-100 text-blue-800"}
   label ": " [:strong (str value)]])

(defn render-lint-results
  "Render lint results as an HTML fragment (for htmx injection)."
  [{:keys [results]}]
  (let [{:keys [valid? errors warnings info]} results
        error-count (count errors)
        warning-count (count warnings)]
    (str
      (h/html
        [:div {:id "lint-results" :class "space-y-4 animate-fade-in"}
         ;; Summary banner
         [:div {:class (str "rounded-lg border p-4 "
                            (cond
                              (not valid?) "bg-red-50 border-red-200"
                              (pos? warning-count) "bg-yellow-50 border-yellow-200"
                              :else "bg-green-50 border-green-200"))}
          [:div {:class "flex items-center gap-3"}
           [:span {:class (str "text-2xl "
                               (cond
                                 (not valid?) "text-red-500"
                                 (pos? warning-count) "text-yellow-500"
                                 :else "text-green-500"))}
            (cond
              (not valid?) "FAIL"
              (pos? warning-count) "WARN"
              :else "PASS")]
           [:div
            [:div {:class "text-sm font-medium text-gray-900 dark:text-gray-100"}
             (cond
               (not valid?) (str error-count " error" (when (> error-count 1) "s") " found")
               (pos? warning-count) (str "Valid with " warning-count " warning" (when (> warning-count 1) "s"))
               :else "Pipeline is valid -- no issues found")]
            (when (pos? warning-count)
              [:div {:class "text-xs text-gray-500 mt-0.5"}
               (str warning-count " warning" (when (> warning-count 1) "s"))])]]]

         ;; Info summary
         (when info
           [:div {:class "bg-blue-50 border border-blue-200 rounded-lg p-4"}
            [:div {:class "text-sm font-medium text-blue-900 mb-2"} "Pipeline Info"]
            [:div {:class "flex flex-wrap gap-2"}
             (info-badge "Format" (or (:format info) "?"))
             (info-badge "Stages" (:stages info))
             (info-badge "Steps" (:steps info))
             (when (:has-dag? info)
               (info-badge "DAG" "yes"))
             (when (:has-matrix? info)
               (info-badge "Matrix" "yes"))
             (when (:has-docker? info)
               (info-badge "Docker" "yes"))
             (when (:has-approval? info)
               (info-badge "Approvals" "yes"))]])

         ;; Errors
         (when (seq errors)
           [:div {:class "space-y-2"}
            [:h3 {:class "text-sm font-semibold text-red-800"}
             (str "Errors (" error-count ")")]
            (for [e errors]
              (error-item e))])

         ;; Warnings
         (when (seq warnings)
           [:div {:class "space-y-2"}
            [:h3 {:class "text-sm font-semibold text-yellow-800"}
             (str "Warnings (" warning-count ")")]
            (for [w warnings]
              (warning-item w))])]))))

(defn render-linter-page
  "Render the full linter page."
  [{:keys [csrf-token user auth-enabled results input-text format-type]}]
  (layout/base-layout
    {:title "Pipeline Linter" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "Pipeline Linter")

    [:div {:class "grid grid-cols-1 lg:grid-cols-2 gap-6"}
     ;; Left: Input form
     [:div {:class "space-y-4"}
      [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 p-5"}
       [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4"} "Pipeline Content"]
       [:form {:hx-post "/admin/linter/check"
               :hx-target "#lint-results-container"
               :hx-swap "innerHTML"
               :class "space-y-4"}
        [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
        ;; Format selector
        [:div {:class "flex items-center gap-4"}
         [:label {:class "text-sm font-medium text-gray-700"} "Format:"]
         [:select {:name "format"
                   :class "border border-gray-300 rounded px-3 py-1.5 text-sm focus:ring-blue-500 focus:border-blue-500"}
          [:option {:value "edn" :selected (or (nil? format-type) (= format-type "edn"))} "EDN (Chengisfile)"]
          [:option {:value "yaml" :selected (= format-type "yaml")} "YAML"]]]
        ;; Content textarea
        [:textarea {:name "content"
                    :rows "20"
                    :class "w-full font-mono text-sm border border-gray-300 rounded-lg p-3 focus:ring-blue-500 focus:border-blue-500"
                    :placeholder (str "{:description \"My pipeline\"\n"
                                      " :stages [{:name \"Build\"\n"
                                      "           :steps [{:name \"Compile\" :run \"make build\"}]}]}")}
         (when input-text (escape-html input-text))]
        ;; Submit button
        [:button {:type "submit"
                  :class "w-full bg-blue-600 text-white px-4 py-2.5 rounded-lg text-sm font-medium
                          hover:bg-blue-700 active:bg-blue-800 transition-colors
                          focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"}
         "Lint Pipeline"]]]]

     ;; Right: Results
     [:div {:class "space-y-4"}
      [:div {:id "lint-results-container"}
       (if results
         (render-lint-results {:results results})
         [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 p-8 text-center"}
          [:p {:class "text-gray-400 dark:text-gray-500 text-sm"}
           "Paste your pipeline content and click \"Lint Pipeline\" to see results."]])]]]

    ;; Help section
    [:div {:class "bg-white dark:bg-gray-800 rounded-lg shadow-sm border dark:border-gray-700 p-5 mt-6"}
     [:h2 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-3"} "What Gets Checked"]
     [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-4 text-sm text-gray-600 dark:text-gray-400"}
      [:div
       [:h3 {:class "font-medium text-gray-900 dark:text-gray-100 mb-1"} "Structural"]
       [:ul {:class "list-disc list-inside space-y-0.5"}
        [:li "Required fields present"]
        [:li "Stage names unique"]
        [:li "Step names unique within stage"]
        [:li "No empty stages or steps"]
        [:li "Valid step types"]]]
      [:div
       [:h3 {:class "font-medium text-gray-900 dark:text-gray-100 mb-1"} "Semantic"]
       [:ul {:class "list-disc list-inside space-y-0.5"}
        [:li "DAG dependency references"]
        [:li "Circular dependency detection"]
        [:li "Docker image required"]
        [:li "Valid timeout values"]
        [:li "Matrix, parameter, cache config"]]]
      [:div
       [:h3 {:class "font-medium text-gray-900 dark:text-gray-100 mb-1"} "Warnings"]
       [:ul {:class "list-disc list-inside space-y-0.5"}
        [:li "Missing description"]
        [:li "Single-step stages"]
        [:li "Very long timeouts (>1h)"]
        [:li "Duplicate env vars"]
        [:li "Missing source config"]]]]]))
