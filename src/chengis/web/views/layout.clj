(ns chengis.web.views.layout
  (:require [hiccup2.core :as h]
            [hiccup.util :refer [raw-string escape-html]]
            [chengis.web.views.notifications :as notifications]))

(defn- role-badge
  "Render a small role badge."
  [role]
  (let [colors (case (keyword role)
                 :admin "bg-red-500"
                 :developer "bg-blue-500"
                 :viewer "bg-gray-500"
                 "bg-gray-500")]
    [:span {:class (str "ml-1 px-1.5 py-0.5 text-xs rounded " colors " text-white")}
     (name (or role :viewer))]))

(defn base-layout
  "Wrap page content in a full HTML document with Tailwind CSS and htmx.
   Options: :title, :csrf-token, :user (from auth middleware), :auth-enabled.
   Supports responsive mobile layout and dark/light theme toggle."
  [{:keys [title csrf-token user auth-enabled notifications-enabled]} & body]
  (str
    (h/html
      (raw-string "<!DOCTYPE html>")
      [:html {:lang "en"}
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        (when csrf-token
          [:meta {:name "csrf-token" :content (escape-html csrf-token)}])
        [:title (str title " | Chengis CI")]
        [:script {:src "https://cdn.tailwindcss.com"}]
        ;; Configure Tailwind dark mode via class strategy
        [:script (raw-string "tailwind.config = { darkMode: 'class' }")]
        ;; Apply saved theme on page load (before render to avoid flash)
        [:script (raw-string
          "if (localStorage.getItem('theme') === 'dark' ||
              (!localStorage.getItem('theme') && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
            document.documentElement.classList.add('dark');
          }")]
        [:script {:src "https://unpkg.com/htmx.org@2.0.4"
                  :integrity "sha384-HGfztofotfshcF7+8n44JQL2oJmowVChPTg48S+jvZoztPfvwD79OC/LTtG6dMp+"
                  :crossorigin "anonymous"}]
        [:script {:src "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js"
                  :integrity "sha384-fw+eTlCc7suMV/1w/7fr2/PmwElUIt5i82bi+qTiLXvjRXZ2/FkiTNA/w0MhXnGI"
                  :crossorigin "anonymous"}]
        ;; Configure htmx to send CSRF token with every request
        (when csrf-token
          [:script (raw-string
            "document.addEventListener('DOMContentLoaded', function() {
               document.body.addEventListener('htmx:configRequest', function(e) {
                 var token = document.querySelector('meta[name=\"csrf-token\"]');
                 if (token) { e.detail.headers['X-CSRF-Token'] = token.content; }
               });
             });")])
        [:style (raw-string "
          @keyframes fade-in { from { opacity: 0; } to { opacity: 1; } }
          .animate-fade-in { animation: fade-in 0.3s ease-in; }
          .log-container { scroll-behavior: smooth; }
          .log-container > div:last-child { animation: fade-in 0.15s ease-in; }
        ")]]
       [:body {:class "bg-gray-50 dark:bg-gray-900 text-gray-900 dark:text-gray-100 min-h-screen flex flex-col"}
        ;; Nav
        [:nav {:class "bg-gray-900 dark:bg-gray-950 text-white shadow-lg"}
         [:div {:class "max-w-7xl mx-auto px-4 sm:px-6 py-3 flex items-center justify-between"}
          [:a {:href "/" :class "text-xl font-bold tracking-wide hover:text-blue-300 transition"}
           "Chengis CI"]
          ;; Hamburger toggle for mobile (CSS-only via hidden checkbox + peer)
          [:input {:type "checkbox" :id "nav-toggle" :class "hidden peer" :aria-label "Toggle navigation"}]
          [:label {:for "nav-toggle"
                   :class "md:hidden cursor-pointer text-2xl select-none hover:text-blue-300 transition"
                   :aria-label "Toggle navigation menu"}
           "\u2630"]
          ;; Nav links: hidden on mobile, shown when hamburger checked or on md+
          [:div {:class "hidden peer-checked:flex md:flex flex-col md:flex-row items-start md:items-center
                         gap-2 md:gap-6 text-sm font-medium
                         absolute md:static top-14 left-0 right-0
                         bg-gray-900 dark:bg-gray-950 md:bg-transparent
                         px-4 md:px-0 py-3 md:py-0 shadow-lg md:shadow-none z-50"}
           [:a {:href "/" :class "hover:text-blue-300 transition w-full md:w-auto py-1 md:py-0"} "Dashboard"]
           [:a {:href "/jobs" :class "hover:text-blue-300 transition w-full md:w-auto py-1 md:py-0"} "Jobs"]
           [:a {:href "/agents" :class "hover:text-blue-300 transition w-full md:w-auto py-1 md:py-0"} "Agents"]
           [:a {:href "/analytics" :class "hover:text-blue-300 transition w-full md:w-auto py-1 md:py-0"} "Analytics"]
           [:a {:href "/deploy" :class "hover:text-blue-300 transition w-full md:w-auto py-1 md:py-0"} "Deploy"]
           [:a {:href "/iac" :class "hover:text-blue-300 transition w-full md:w-auto py-1 md:py-0"} "Infra"]
           [:a {:href "/search/logs" :class "hover:text-blue-300 transition w-full md:w-auto py-1 md:py-0"} "Search"]
           (when (and auth-enabled user)
             [:a {:href "/settings/tokens" :class "hover:text-blue-300 transition w-full md:w-auto py-1 md:py-0"} "Settings"])
           (when (or (not auth-enabled)
                     (and user (= (keyword (:role user)) :admin)))
             [:a {:href "/admin" :class "hover:text-blue-300 transition w-full md:w-auto py-1 md:py-0"} "Admin"])
           ;; Theme toggle button
           [:button {:id "theme-toggle"
                     :class "text-gray-400 hover:text-yellow-300 transition text-sm cursor-pointer"
                     :title "Toggle dark/light theme"
                     :onclick (str
                       "document.documentElement.classList.toggle('dark');"
                       "localStorage.setItem('theme', "
                       "document.documentElement.classList.contains('dark') ? 'dark' : 'light');")}
            "\uD83C\uDF13"]
           ;; Notification toggle
           (when notifications-enabled
             (notifications/notification-toggle))
           ;; User info / login-logout
           (if (and auth-enabled user)
             [:div {:class "flex items-center gap-2 ml-0 md:ml-4 pl-0 md:pl-4 border-l-0 md:border-l border-gray-600
                            w-full md:w-auto pt-2 md:pt-0 mt-2 md:mt-0 border-t md:border-t-0 border-gray-700"}
              [:span {:class "text-gray-300"} (:username user)]
              (role-badge (:role user))
              [:form {:method "POST" :action "/logout" :class "inline"}
               (when csrf-token
                 [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
               [:button {:type "submit"
                         :class "text-gray-400 hover:text-red-300 transition text-xs"}
                "Logout"]]]
             (when auth-enabled
               [:a {:href "/login" :class "ml-0 md:ml-4 pl-0 md:pl-4 border-l-0 md:border-l border-gray-600 hover:text-blue-300 transition
                                           w-full md:w-auto py-1 md:py-0"}
                "Login"]))]]]
        ;; Content
        [:main {:class "max-w-7xl mx-auto px-4 sm:px-6 py-8 flex-1 w-full"}
         body]
        ;; Footer
        [:footer {:class "border-t dark:border-gray-700 mt-auto"}
         [:div {:class "max-w-7xl mx-auto px-4 py-4 text-center text-gray-400 dark:text-gray-500 text-xs"}
          "Chengis CI v0.2.0"]]
        ;; Browser notification script (when enabled)
        (when notifications-enabled
          (notifications/notification-script))]])))
