(ns chengis.web.views.layout
  (:require [hiccup2.core :as h]
            [hiccup.util :refer [raw-string escape-html]]))

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
   Options: :title, :csrf-token, :user (from auth middleware), :auth-enabled."
  [{:keys [title csrf-token user auth-enabled]} & body]
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
        [:script {:src "https://unpkg.com/htmx.org@2.0.4"
                  :crossorigin "anonymous"}]
        [:script {:src "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js"
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
       [:body {:class "bg-gray-50 min-h-screen flex flex-col"}
        ;; Nav
        [:nav {:class "bg-gray-900 text-white shadow-lg"}
         [:div {:class "max-w-7xl mx-auto px-4 sm:px-6 py-3 flex items-center justify-between"}
          [:a {:href "/" :class "text-xl font-bold tracking-wide hover:text-blue-300 transition"}
           "Chengis CI"]
          [:div {:class "flex items-center gap-6 text-sm font-medium"}
           [:a {:href "/" :class "hover:text-blue-300 transition"} "Dashboard"]
           [:a {:href "/jobs" :class "hover:text-blue-300 transition"} "Jobs"]
           [:a {:href "/agents" :class "hover:text-blue-300 transition"} "Agents"]
           (when (and auth-enabled user)
             [:a {:href "/settings/tokens" :class "hover:text-blue-300 transition"} "Settings"])
           (when (or (not auth-enabled)
                     (and user (= (keyword (:role user)) :admin)))
             [:a {:href "/admin" :class "hover:text-blue-300 transition"} "Admin"])
           ;; User info / login-logout
           (if (and auth-enabled user)
             [:div {:class "flex items-center gap-2 ml-4 pl-4 border-l border-gray-600"}
              [:span {:class "text-gray-300"} (:username user)]
              (role-badge (:role user))
              [:form {:method "POST" :action "/logout" :class "inline"}
               (when csrf-token
                 [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
               [:button {:type "submit"
                         :class "text-gray-400 hover:text-red-300 transition text-xs"}
                "Logout"]]]
             (when auth-enabled
               [:a {:href "/login" :class "ml-4 pl-4 border-l border-gray-600 hover:text-blue-300 transition"}
                "Login"]))]]]
        ;; Content
        [:main {:class "max-w-7xl mx-auto px-4 sm:px-6 py-8 flex-1 w-full"}
         body]
        ;; Footer
        [:footer {:class "border-t mt-auto"}
         [:div {:class "max-w-7xl mx-auto px-4 py-4 text-center text-gray-400 text-xs"}
          "Chengis CI v0.2.0"]]]])))
