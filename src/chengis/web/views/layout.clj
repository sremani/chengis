(ns chengis.web.views.layout
  (:require [hiccup2.core :as h]
            [hiccup.util :refer [raw-string escape-html]]))

(defn base-layout
  "Wrap page content in a full HTML document with Tailwind CSS and htmx."
  [{:keys [title csrf-token]} & body]
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
          [:div {:class "flex gap-6 text-sm font-medium"}
           [:a {:href "/" :class "hover:text-blue-300 transition"} "Dashboard"]
           [:a {:href "/jobs" :class "hover:text-blue-300 transition"} "Jobs"]
           [:a {:href "/admin" :class "hover:text-blue-300 transition"} "Admin"]]]]
        ;; Content
        [:main {:class "max-w-7xl mx-auto px-4 sm:px-6 py-8 flex-1 w-full"}
         body]
        ;; Footer
        [:footer {:class "border-t mt-auto"}
         [:div {:class "max-w-7xl mx-auto px-4 py-4 text-center text-gray-400 text-xs"}
          "Chengis CI v0.1.0"]]]])))
