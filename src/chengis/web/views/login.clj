(ns chengis.web.views.login
  "Login page view."
  (:require [hiccup2.core :as h]
            [hiccup.util :refer [raw-string escape-html]]))

(defn render
  "Render the login page. Options: :error, :csrf-token, :oidc-enabled, :oidc-provider-name."
  [{:keys [error csrf-token oidc-enabled oidc-provider-name]}]
  (str
    (h/html
      (raw-string "<!DOCTYPE html>")
      [:html {:lang "en"}
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title "Login | Chengis CI"]
        [:script {:src "https://cdn.tailwindcss.com"}]]
       [:body {:class "bg-gray-50 min-h-screen flex items-center justify-center"}
        [:div {:class "w-full max-w-md"}
         ;; Logo/title
         [:div {:class "text-center mb-8"}
          [:h1 {:class "text-3xl font-bold text-gray-900"} "Chengis CI"]
          [:p {:class "text-gray-500 mt-2"} "Sign in to continue"]]
         ;; Card
         [:div {:class "bg-white rounded-lg shadow-md p-8"}
          ;; Error message
          (when error
            [:div {:class "bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded mb-4"}
             (escape-html error)])
          ;; SSO button (when OIDC is enabled)
          (when oidc-enabled
            [:div {:class "mb-6"}
             [:a {:href "/auth/oidc/login"
                  :class "w-full flex items-center justify-center bg-gray-800 text-white py-2 px-4 rounded-md hover:bg-gray-900 transition font-medium"}
              (str "Sign in with " (or oidc-provider-name "SSO"))]
             [:div {:class "relative my-4"}
              [:div {:class "absolute inset-0 flex items-center"}
               [:div {:class "w-full border-t border-gray-300"}]]
              [:div {:class "relative flex justify-center text-sm"}
               [:span {:class "px-2 bg-white text-gray-500"} "or sign in with password"]]]])
          ;; Login form
          [:form {:method "POST" :action "/login"}
           (when csrf-token
             [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
           [:div {:class "mb-4"}
            [:label {:for "username" :class "block text-sm font-medium text-gray-700 mb-1"}
             "Username"]
            [:input {:type "text" :id "username" :name "username"
                     :class "w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                     :placeholder "admin"
                     :required true
                     :autofocus true}]]
           [:div {:class "mb-6"}
            [:label {:for "password" :class "block text-sm font-medium text-gray-700 mb-1"}
             "Password"]
            [:input {:type "password" :id "password" :name "password"
                     :class "w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                     :placeholder "Enter your password"
                     :required true}]]
           [:button {:type "submit"
                     :class "w-full bg-blue-600 text-white py-2 px-4 rounded-md hover:bg-blue-700 transition font-medium"}
            "Sign In"]]]
         ;; Footer
         [:p {:class "text-center text-gray-400 text-xs mt-6"}
          "Chengis CI v0.8.0"]]]])))
