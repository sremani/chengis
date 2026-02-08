(ns chengis.web.views.tokens
  "API token management page: list, generate, revoke tokens."
  (:require [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [hiccup.util :refer [escape-html]]))

(defn- token-status-badge
  "Badge showing token status (active, expired, revoked)."
  [token]
  (cond
    (:revoked-at token)
    [:span {:class "px-2 py-0.5 text-xs font-medium rounded bg-red-100 text-red-800"}
     "Revoked"]

    (and (:expires-at token)
         (try
           (.isAfter (java.time.Instant/now) (java.time.Instant/parse (:expires-at token)))
           (catch Exception _ false)))
    [:span {:class "px-2 py-0.5 text-xs font-medium rounded bg-yellow-100 text-yellow-800"}
     "Expired"]

    :else
    [:span {:class "px-2 py-0.5 text-xs font-medium rounded bg-green-100 text-green-800"}
     "Active"]))

(defn- token-row
  "Single token table row."
  [token csrf-token]
  [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
   [:td {:class "py-2 px-3 text-sm font-medium"} (escape-html (:name token))]
   [:td {:class "py-2 px-3"} (token-status-badge token)]
   [:td {:class "py-2 px-3 text-xs text-gray-500"} (:created-at token)]
   [:td {:class "py-2 px-3 text-xs text-gray-500"} (or (:last-used-at token) "Never")]
   [:td {:class "py-2 px-3 text-xs text-gray-500"} (or (:expires-at token) "Never")]
   [:td {:class "py-2 px-3"}
    (when-not (:revoked-at token)
      [:form {:method "POST" :action (str "/settings/tokens/" (:id token) "/revoke")}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:button {:type "submit"
                 :class "text-xs text-red-600 hover:text-red-800 font-medium"
                 :onclick "return confirm('Revoke this token? This cannot be undone.')"}
        "Revoke"]])]])

(defn render
  "API token management page."
  [{:keys [tokens new-token message error csrf-token user auth-enabled]}]
  (layout/base-layout
    {:title "API Tokens" :csrf-token csrf-token :user user :auth-enabled auth-enabled}
    (c/page-header "API Tokens")

    ;; New token banner (one-time display)
    (when new-token
      [:div {:class "bg-yellow-50 border border-yellow-200 rounded-lg p-4 mb-6"}
       [:h3 {:class "text-sm font-semibold text-yellow-800 mb-2"}
        "New Token Created — Copy It Now!"]
       [:p {:class "text-xs text-yellow-700 mb-2"}
        "This token will not be shown again. Store it securely."]
       [:code {:class "block p-3 bg-yellow-100 rounded text-sm font-mono text-yellow-900 break-all select-all"}
        (escape-html new-token)]])

    ;; Flash messages
    (when message
      [:div {:class "bg-green-50 border border-green-200 rounded-lg p-3 mb-4 text-sm text-green-700"}
       message])
    (when error
      [:div {:class "bg-red-50 border border-red-200 rounded-lg p-3 mb-4 text-sm text-red-700"}
       error])

    ;; Generate form
    [:div {:class "bg-white rounded-lg shadow-sm border p-5 mb-6"}
     [:h2 {:class "text-lg font-semibold text-gray-900 mb-4"} "Generate New Token"]
     [:form {:method "POST" :action "/settings/tokens" :class "flex flex-wrap items-end gap-4"}
      [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
      [:div
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Token Name"]
       [:input {:type "text" :name "name" :required true
                :placeholder "e.g., CI Pipeline"
                :class "px-3 py-2 border rounded text-sm w-64 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"}]]
      [:div
       [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Expires In"]
       [:select {:name "expires-in"
                 :class "px-3 py-2 border rounded text-sm focus:ring-2 focus:ring-blue-500"}
        [:option {:value "7"} "7 days"]
        [:option {:value "30"} "30 days"]
        [:option {:value "90" :selected true} "90 days"]
        [:option {:value "365"} "1 year"]
        [:option {:value ""} "Never"]]]
      [:button {:type "submit"
                :class "bg-blue-600 text-white px-4 py-2 rounded text-sm font-medium hover:bg-blue-700 transition"}
       "Generate Token"]]]

    ;; Token list
    [:div {:class "bg-white rounded-lg shadow-sm border overflow-hidden"}
     [:div {:class "px-5 py-3 border-b bg-gray-50"}
      [:span {:class "text-sm font-medium text-gray-700"}
       (str (count tokens) " token" (when (not= 1 (count tokens)) "s"))]]
     (if (empty? tokens)
       [:div {:class "p-8 text-center text-gray-500"}
        "No API tokens. Generate one above to use with the Chengis API."]
       [:table {:class "w-full text-sm"}
        [:thead
         [:tr {:class "text-left text-gray-500 border-b bg-gray-50"}
          [:th {:class "py-2 px-3 font-medium"} "Name"]
          [:th {:class "py-2 px-3 font-medium"} "Status"]
          [:th {:class "py-2 px-3 font-medium"} "Created"]
          [:th {:class "py-2 px-3 font-medium"} "Last Used"]
          [:th {:class "py-2 px-3 font-medium"} "Expires"]
          [:th {:class "py-2 px-3 font-medium"} "Actions"]]]
        [:tbody
         (for [token tokens]
           (token-row token csrf-token))]])]))
