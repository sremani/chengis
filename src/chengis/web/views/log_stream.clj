(ns chengis.web.views.log-stream
  "Chunked log rendering with htmx infinite scroll for large build logs."
  (:require [chengis.db.log-chunk-store :as log-chunk-store]))

(defn render-log-chunks
  "Render a set of log chunks as HTML pre blocks."
  [chunks]
  (if (seq chunks)
    [:div {:class "log-chunks"}
     (for [chunk chunks]
       [:pre {:class "text-xs font-mono bg-gray-900 text-green-400 p-2 mb-0 whitespace-pre-wrap"
              :data-chunk-index (:chunk-index chunk)}
        (:content chunk)])]
    [:div {:class "text-gray-400 dark:text-gray-500 text-center py-4"}
     "No log output"]))

(defn render-chunk-loader
  "Render an htmx sentinel div that loads the next batch of chunks on scroll.
   Uses hx-trigger='revealed' for infinite scroll."
  [build-id step-id source next-offset limit]
  [:div {:hx-get (str "/builds/" build-id "/log/chunks"
                       "?step-id=" step-id
                       "&source=" (or source "stdout")
                       "&offset=" next-offset
                       "&limit=" limit)
         :hx-trigger "revealed"
         :hx-swap "outerHTML"
         :class "text-center py-2 text-gray-400"}
   [:span {:class "animate-pulse"} "Loading more..."]])

(defn render-chunked-log
  "Render the initial chunked log view for a step.
   Shows first batch of chunks plus a loader sentinel for more."
  [ds build-id step-id & {:keys [source initial-chunks]
                           :or {source "stdout" initial-chunks 5}}]
  (let [chunks (log-chunk-store/get-chunks ds step-id
                 :source source :offset 0 :limit initial-chunks)
        total-chunks (log-chunk-store/get-chunk-count ds step-id :source source)
        has-more (> total-chunks initial-chunks)]
    [:div {:class "chunked-log" :data-step-id step-id}
     (render-log-chunks chunks)
     (when has-more
       (render-chunk-loader build-id step-id source initial-chunks initial-chunks))]))

(defn render-chunk-fragment
  "Render a fragment of chunks for htmx lazy loading.
   Called by the /builds/:id/log/chunks endpoint."
  [ds build-id step-id source offset limit]
  (let [chunks (log-chunk-store/get-chunks ds step-id
                 :source source :offset offset :limit limit)
        total-chunks (log-chunk-store/get-chunk-count ds step-id :source source)
        next-offset (+ offset limit)
        has-more (> total-chunks next-offset)]
    [:div
     (render-log-chunks chunks)
     (when has-more
       (render-chunk-loader build-id step-id source next-offset limit))]))
