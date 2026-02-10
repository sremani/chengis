(ns chengis.web.views.mfa
  "Views for MFA/TOTP setup, challenge, and recovery."
  (:require [chengis.web.views.layout :as layout]
            [hiccup2.core :as h]
            [hiccup.util :refer [raw-string escape-html]]))

;; ---------------------------------------------------------------------------
;; Setup page — shown after enrollment begins
;; ---------------------------------------------------------------------------

(defn render-setup-page
  "Render the MFA setup page with QR code, recovery codes, and confirmation form.
   Options: :secret-b32, :totp-uri, :recovery-codes, :csrf-token, :user, :auth-enabled, :error."
  [{:keys [secret-b32 totp-uri recovery-codes csrf-token user auth-enabled error]}]
  (layout/base-layout
    {:title "MFA Setup" :csrf-token csrf-token :user user :auth-enabled auth-enabled}

    [:div {:class "max-w-2xl mx-auto"}
     [:h1 {:class "text-2xl font-bold text-gray-900 mb-6"} "Set Up Two-Factor Authentication"]

     ;; Error message
     (when error
       [:div {:class "bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded mb-4"}
        (escape-html error)])

     ;; Step 1: QR code
     [:div {:class "bg-white rounded-lg shadow-sm border p-6 mb-6"}
      [:h2 {:class "text-lg font-semibold text-gray-900 mb-3"} "1. Scan QR Code"]
      [:p {:class "text-sm text-gray-600 mb-4"}
       "Scan this QR code with your authenticator app (Google Authenticator, Authy, 1Password, etc.)"]
      [:div {:class "flex justify-center mb-4"}
       [:img {:src (str "https://chart.googleapis.com/chart?chs=200x200&cht=qr&chl="
                        (java.net.URLEncoder/encode (str totp-uri) "UTF-8"))
              :alt "TOTP QR Code"
              :class "border rounded"
              :width "200"
              :height "200"}]]
      [:div {:class "text-center"}
       [:p {:class "text-xs text-gray-500 mb-1"} "Or enter this secret manually:"]
       [:code {:class "text-sm font-mono bg-gray-100 px-3 py-1 rounded select-all"}
        (escape-html secret-b32)]]]

     ;; Step 2: Recovery codes
     [:div {:class "bg-yellow-50 border border-yellow-200 rounded-lg p-6 mb-6"}
      [:h2 {:class "text-lg font-semibold text-yellow-800 mb-3"}
       "2. Save Recovery Codes"]
      [:p {:class "text-sm text-yellow-700 mb-4"}
       "Store these codes in a safe place. Each code can only be used once. "
       "They allow you to sign in if you lose access to your authenticator app."]
      [:div {:class "grid grid-cols-2 gap-2 mb-4"}
       (for [code recovery-codes]
         [:code {:class "block text-center bg-white border border-yellow-300 rounded px-3 py-2 text-sm font-mono select-all"}
          (escape-html code)])]
      [:p {:class "text-xs text-yellow-600"}
       "These codes will not be shown again."]]

     ;; Step 3: Confirm
     [:div {:class "bg-white rounded-lg shadow-sm border p-6"}
      [:h2 {:class "text-lg font-semibold text-gray-900 mb-3"} "3. Confirm Setup"]
      [:p {:class "text-sm text-gray-600 mb-4"}
       "Enter a code from your authenticator app to verify setup."]
      [:form {:method "POST" :action "/settings/mfa/confirm"}
       (when csrf-token
         [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
       [:div {:class "flex items-end gap-3"}
        [:div
         [:label {:for "code" :class "block text-sm font-medium text-gray-700 mb-1"}
          "Verification Code"]
         [:input {:type "text" :id "code" :name "code"
                  :class "w-48 px-3 py-2 border border-gray-300 rounded-md text-center text-lg tracking-widest font-mono focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                  :placeholder "000000"
                  :maxlength "6"
                  :pattern "\\d{6}"
                  :inputmode "numeric"
                  :autocomplete "one-time-code"
                  :required true
                  :autofocus true}]]
        [:button {:type "submit"
                  :class "bg-blue-600 text-white px-6 py-2 rounded-md hover:bg-blue-700 transition font-medium"}
         "Verify & Enable"]]]]]))

;; ---------------------------------------------------------------------------
;; Challenge page — shown during login when MFA is required
;; ---------------------------------------------------------------------------

(defn render-challenge-page
  "Render the MFA challenge page for login. Minimal page (no nav).
   Options: :error, :csrf-token."
  [{:keys [error csrf-token]}]
  (str
    (h/html
      (raw-string "<!DOCTYPE html>")
      [:html {:lang "en"}
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title "Two-Factor Authentication | Chengis CI"]
        [:script {:src "https://cdn.tailwindcss.com"}]]
       [:body {:class "bg-gray-50 min-h-screen flex items-center justify-center"}
        [:div {:class "w-full max-w-md"}
         ;; Logo/title
         [:div {:class "text-center mb-8"}
          [:h1 {:class "text-3xl font-bold text-gray-900"} "Chengis CI"]
          [:p {:class "text-gray-500 mt-2"} "Two-factor authentication"]]
         ;; Card
         [:div {:class "bg-white rounded-lg shadow-md p-8"}
          ;; Error
          (when error
            [:div {:class "bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded mb-4"}
             (escape-html error)])
          ;; Instructions
          [:p {:class "text-sm text-gray-600 mb-6"}
           "Enter the 6-digit code from your authenticator app to continue."]
          ;; Code form
          [:form {:method "POST" :action "/auth/mfa/challenge"}
           (when csrf-token
             [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
           [:div {:class "mb-6"}
            [:label {:for "code" :class "block text-sm font-medium text-gray-700 mb-1"}
             "Authentication Code"]
            [:input {:type "text" :id "code" :name "code"
                     :class "w-full px-3 py-3 border border-gray-300 rounded-md text-center text-xl tracking-widest font-mono focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                     :placeholder "000000"
                     :maxlength "6"
                     :pattern "\\d{6}"
                     :inputmode "numeric"
                     :autocomplete "one-time-code"
                     :required true
                     :autofocus true}]]
           [:button {:type "submit"
                     :class "w-full bg-blue-600 text-white py-2 px-4 rounded-md hover:bg-blue-700 transition font-medium"}
            "Verify"]]
          ;; Recovery link
          [:div {:class "mt-4 text-center"}
           [:a {:href "/auth/mfa/recovery"
                :class "text-sm text-blue-600 hover:text-blue-800 hover:underline"}
            "Use a recovery code instead"]]]
         ;; Footer
         [:p {:class "text-center text-gray-400 text-xs mt-6"}
          "Chengis CI v0.8.0"]]]])))

;; ---------------------------------------------------------------------------
;; Recovery page — enter a recovery code when authenticator is unavailable
;; ---------------------------------------------------------------------------

(defn render-recovery-page
  "Render the recovery code entry page. Minimal page (no nav).
   Options: :error, :csrf-token."
  [{:keys [error csrf-token]}]
  (str
    (h/html
      (raw-string "<!DOCTYPE html>")
      [:html {:lang "en"}
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title "Recovery Code | Chengis CI"]
        [:script {:src "https://cdn.tailwindcss.com"}]]
       [:body {:class "bg-gray-50 min-h-screen flex items-center justify-center"}
        [:div {:class "w-full max-w-md"}
         ;; Logo/title
         [:div {:class "text-center mb-8"}
          [:h1 {:class "text-3xl font-bold text-gray-900"} "Chengis CI"]
          [:p {:class "text-gray-500 mt-2"} "Account recovery"]]
         ;; Card
         [:div {:class "bg-white rounded-lg shadow-md p-8"}
          ;; Error
          (when error
            [:div {:class "bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded mb-4"}
             (escape-html error)])
          ;; Instructions
          [:p {:class "text-sm text-gray-600 mb-6"}
           "Enter one of your recovery codes. Each code can only be used once."]
          ;; Recovery code form
          [:form {:method "POST" :action "/auth/mfa/recovery"}
           (when csrf-token
             [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
           [:div {:class "mb-6"}
            [:label {:for "recovery-code" :class "block text-sm font-medium text-gray-700 mb-1"}
             "Recovery Code"]
            [:input {:type "text" :id "recovery-code" :name "recovery-code"
                     :class "w-full px-3 py-3 border border-gray-300 rounded-md text-center text-lg tracking-widest font-mono focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                     :placeholder "XXXX-XXXX"
                     :autocomplete "off"
                     :required true
                     :autofocus true}]]
           [:button {:type "submit"
                     :class "w-full bg-blue-600 text-white py-2 px-4 rounded-md hover:bg-blue-700 transition font-medium"}
            "Verify Recovery Code"]]
          ;; Back link
          [:div {:class "mt-4 text-center"}
           [:a {:href "/auth/mfa/challenge"
                :class "text-sm text-blue-600 hover:text-blue-800 hover:underline"}
            "Back to authenticator code"]]]
         ;; Footer
         [:p {:class "text-center text-gray-400 text-xs mt-6"}
          "Chengis CI v0.8.0"]]]])))

;; ---------------------------------------------------------------------------
;; Settings page — MFA status and enable/disable controls
;; ---------------------------------------------------------------------------

(defn render-settings-page
  "Render the MFA settings page showing status and setup/disable buttons.
   Options: :mfa-enabled, :csrf-token, :user, :auth-enabled, :message, :error."
  [{:keys [mfa-enabled csrf-token user auth-enabled message error]}]
  (layout/base-layout
    {:title "MFA Settings" :csrf-token csrf-token :user user :auth-enabled auth-enabled}

    [:div {:class "max-w-2xl mx-auto"}
     [:h1 {:class "text-2xl font-bold text-gray-900 mb-6"} "Two-Factor Authentication"]

     ;; Flash messages
     (when message
       [:div {:class "bg-green-50 border border-green-200 rounded-lg p-3 mb-4 text-sm text-green-700"}
        (escape-html message)])
     (when error
       [:div {:class "bg-red-50 border border-red-200 rounded-lg p-3 mb-4 text-sm text-red-700"}
        (escape-html error)])

     [:div {:class "bg-white rounded-lg shadow-sm border p-6"}
      ;; Status
      [:div {:class "flex items-center justify-between mb-6"}
       [:div
        [:h2 {:class "text-lg font-semibold text-gray-900"} "Status"]
        [:p {:class "text-sm text-gray-600 mt-1"}
         "Two-factor authentication adds an extra layer of security to your account."]]
       (if mfa-enabled
         [:span {:class "px-3 py-1 bg-green-100 text-green-800 text-sm font-medium rounded-full"}
          "Enabled"]
         [:span {:class "px-3 py-1 bg-gray-100 text-gray-600 text-sm font-medium rounded-full"}
          "Disabled"])]

      ;; Action
      (if mfa-enabled
        ;; Disable button
        [:form {:method "POST" :action "/settings/mfa/disable"}
         (when csrf-token
           [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
         [:div {:class "border-t pt-4"}
          [:p {:class "text-sm text-gray-600 mb-3"}
           "Disabling MFA will remove the requirement for a second factor during login."]
          [:button {:type "submit"
                    :class "bg-red-600 text-white px-4 py-2 rounded-md hover:bg-red-700 transition font-medium text-sm"
                    :onclick "return confirm('Are you sure you want to disable two-factor authentication?')"}
           "Disable MFA"]]]
        ;; Enable / setup button
        [:form {:method "POST" :action "/settings/mfa/setup"}
         (when csrf-token
           [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
         [:div {:class "border-t pt-4"}
          [:p {:class "text-sm text-gray-600 mb-3"}
           "Protect your account with a time-based one-time password (TOTP) from an authenticator app."]
          [:button {:type "submit"
                    :class "bg-blue-600 text-white px-4 py-2 rounded-md hover:bg-blue-700 transition font-medium text-sm"}
           "Set Up MFA"]]])]]))
