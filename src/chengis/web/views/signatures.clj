(ns chengis.web.views.signatures
  "Hiccup views for artifact signature management."
  (:require [hiccup2.core :as h]))

;; ---------------------------------------------------------------------------
;; Signatures panel
;; ---------------------------------------------------------------------------

(defn signatures-panel
  "Render the artifact signatures listing."
  [{:keys [signatures csrf-token]}]
  [:div {:class "bg-white rounded-lg shadow-sm border p-5"}
   [:h3 {:class "text-lg font-semibold text-gray-900 mb-3"} "Artifact Signatures"]

   (if (empty? signatures)
     [:p {:class "text-gray-400 text-sm italic"} "No signed artifacts yet."]
     [:table {:class "w-full text-sm"}
      [:thead
       [:tr {:class "text-left text-gray-500 border-b"}
        [:th {:class "py-2 font-medium"} "Build"]
        [:th {:class "py-2 font-medium"} "Signer"]
        [:th {:class "py-2 font-medium"} "Key"]
        [:th {:class "py-2 font-medium"} "Digest"]
        [:th {:class "py-2 font-medium"} "Verified"]
        [:th {:class "py-2 font-medium"} "Actions"]]]
      [:tbody
       (for [sig signatures]
         [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
          [:td {:class "py-2 font-mono text-xs"}
           (subs (str (:build-id sig)) 0 (min 8 (count (str (:build-id sig)))))]
          [:td {:class "py-2 text-xs"}
           [:span {:class "px-2 py-0.5 rounded text-xs font-medium bg-gray-100 text-gray-700"}
            (:signer sig)]]
          [:td {:class "py-2 text-xs text-gray-600"} (or (:key-reference sig) "—")]
          [:td {:class "py-2 font-mono text-xs text-gray-500"}
           (when (:target-digest sig) (subs (:target-digest sig) 0 (min 12 (count (:target-digest sig)))))]
          [:td {:class "py-2"}
           (if (= 1 (:verified sig))
             [:span {:class "px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800"} "Verified"]
             [:span {:class "px-2 py-0.5 rounded text-xs font-medium bg-yellow-100 text-yellow-800"} "Unverified"])]
          [:td {:class "py-2"}
           (when (not= 1 (:verified sig))
             [:form {:method "POST"
                     :action (str "/api/supply-chain/builds/" (:build-id sig) "/verify")
                     :class "inline"}
              (when csrf-token
                [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])
              [:button {:type "submit"
                        :class "text-blue-600 hover:text-blue-800 text-xs font-medium"}
               "Verify"]])]])]])])
