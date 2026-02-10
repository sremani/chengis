(ns chengis.web.views.supply-chain
  "Hiccup views for supply chain security dashboard."
  (:require [hiccup2.core :as h]
            [chengis.web.views.signatures :as v-signatures]))

;; ---------------------------------------------------------------------------
;; Status badges
;; ---------------------------------------------------------------------------

(defn- severity-badge [severity count]
  (let [[bg text] (case severity
                    "critical" ["bg-red-600 text-white" "Critical"]
                    "high"     ["bg-orange-500 text-white" "High"]
                    "medium"   ["bg-yellow-400 text-gray-900" "Medium"]
                    "low"      ["bg-blue-200 text-blue-900" "Low"]
                    ["bg-gray-200 text-gray-700" severity])]
    [:span {:class (str "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium " bg)}
     (str text ": " count)]))

(defn- pass-badge [passed]
  (if (= 1 passed)
    [:span {:class "px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800"} "Passed"]
    [:span {:class "px-2 py-0.5 rounded text-xs font-medium bg-red-100 text-red-800"} "Failed"]))

;; ---------------------------------------------------------------------------
;; Supply chain dashboard
;; ---------------------------------------------------------------------------

(defn supply-chain-dashboard
  "Main supply chain security overview."
  [{:keys [attestations sboms scans license-reports signatures csrf-token]}]
  [:div {:class "space-y-6"}
   [:h2 {:class "text-xl font-bold text-gray-900"} "Supply Chain Security"]

   ;; Summary cards row
   [:div {:class "grid grid-cols-2 md:grid-cols-5 gap-4"}
    [:div {:class "bg-white rounded-lg shadow p-4 text-center"}
     [:div {:class "text-2xl font-bold text-blue-600"} (count attestations)]
     [:div {:class "text-xs text-gray-500"} "SLSA Attestations"]]
    [:div {:class "bg-white rounded-lg shadow p-4 text-center"}
     [:div {:class "text-2xl font-bold text-purple-600"} (count sboms)]
     [:div {:class "text-xs text-gray-500"} "SBOMs Generated"]]
    [:div {:class "bg-white rounded-lg shadow p-4 text-center"}
     [:div {:class "text-2xl font-bold text-orange-600"} (count scans)]
     [:div {:class "text-xs text-gray-500"} "Vulnerability Scans"]]
    [:div {:class "bg-white rounded-lg shadow p-4 text-center"}
     [:div {:class "text-2xl font-bold text-green-600"} (count license-reports)]
     [:div {:class "text-xs text-gray-500"} "License Reports"]]
    [:div {:class "bg-white rounded-lg shadow p-4 text-center"}
     [:div {:class "text-2xl font-bold text-indigo-600"} (count signatures)]
     [:div {:class "text-xs text-gray-500"} "Signed Artifacts"]]]

   ;; Recent vulnerability scans
   [:div {:class "bg-white rounded-lg shadow-sm border p-5"}
    [:h3 {:class "text-lg font-semibold text-gray-900 mb-3"} "Recent Vulnerability Scans"]
    (if (empty? scans)
      [:p {:class "text-gray-400 text-sm italic"} "No scans yet."]
      [:table {:class "w-full text-sm"}
       [:thead
        [:tr {:class "text-left text-gray-500 border-b"}
         [:th {:class "py-2 font-medium"} "Build"]
         [:th {:class "py-2 font-medium"} "Target"]
         [:th {:class "py-2 font-medium"} "Scanner"]
         [:th {:class "py-2 font-medium"} "Vulnerabilities"]
         [:th {:class "py-2 font-medium"} "Status"]]]
       [:tbody
        (for [scan (take 10 scans)]
          [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
           [:td {:class "py-2 font-mono text-xs"} (subs (str (:build-id scan)) 0 (min 8 (count (str (:build-id scan)))))]
           [:td {:class "py-2 text-xs"} (:scan-target scan)]
           [:td {:class "py-2 text-xs"} (:scanner scan)]
           [:td {:class "py-2 space-x-1"}
            (when (pos? (or (:critical-count scan) 0)) (severity-badge "critical" (:critical-count scan)))
            (when (pos? (or (:high-count scan) 0)) (severity-badge "high" (:high-count scan)))
            (when (pos? (or (:medium-count scan) 0)) (severity-badge "medium" (:medium-count scan)))
            (when (pos? (or (:low-count scan) 0)) (severity-badge "low" (:low-count scan)))]
           [:td {:class "py-2"} (pass-badge (:passed scan))]])]])]

   ;; Recent SLSA attestations
   [:div {:class "bg-white rounded-lg shadow-sm border p-5"}
    [:h3 {:class "text-lg font-semibold text-gray-900 mb-3"} "SLSA Provenance Attestations"]
    (if (empty? attestations)
      [:p {:class "text-gray-400 text-sm italic"} "No attestations yet."]
      [:table {:class "w-full text-sm"}
       [:thead
        [:tr {:class "text-left text-gray-500 border-b"}
         [:th {:class "py-2 font-medium"} "Build"]
         [:th {:class "py-2 font-medium"} "SLSA Level"]
         [:th {:class "py-2 font-medium"} "Source"]
         [:th {:class "py-2 font-medium"} "Created"]]]
       [:tbody
        (for [att (take 10 attestations)]
          [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
           [:td {:class "py-2 font-mono text-xs"} (subs (str (:build-id att)) 0 (min 8 (count (str (:build-id att)))))]
           [:td {:class "py-2"}
            [:span {:class "px-2 py-0.5 rounded text-xs font-medium bg-blue-100 text-blue-800"}
             (:slsa-level att)]]
           [:td {:class "py-2 text-xs text-gray-600"} (or (:source-repo att) "—")]
           [:td {:class "py-2 text-xs text-gray-600"} (when (:created-at att) (subs (:created-at att) 0 (min 10 (count (:created-at att)))))]])]])]

   ;; Recent SBOMs
   [:div {:class "bg-white rounded-lg shadow-sm border p-5"}
    [:h3 {:class "text-lg font-semibold text-gray-900 mb-3"} "Software Bill of Materials"]
    (if (empty? sboms)
      [:p {:class "text-gray-400 text-sm italic"} "No SBOMs generated yet."]
      [:table {:class "w-full text-sm"}
       [:thead
        [:tr {:class "text-left text-gray-500 border-b"}
         [:th {:class "py-2 font-medium"} "Build"]
         [:th {:class "py-2 font-medium"} "Format"]
         [:th {:class "py-2 font-medium"} "Components"]
         [:th {:class "py-2 font-medium"} "Tool"]
         [:th {:class "py-2 font-medium"} "Created"]]]
       [:tbody
        (for [sbom (take 10 sboms)]
          [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
           [:td {:class "py-2 font-mono text-xs"} (subs (str (:build-id sbom)) 0 (min 8 (count (str (:build-id sbom)))))]
           [:td {:class "py-2 text-xs"} (:sbom-format sbom)]
           [:td {:class "py-2 text-xs font-semibold"} (or (:component-count sbom) 0)]
           [:td {:class "py-2 text-xs text-gray-600"} (or (:tool-name sbom) "—")]
           [:td {:class "py-2 text-xs text-gray-600"} (when (:created-at sbom) (subs (:created-at sbom) 0 (min 10 (count (:created-at sbom)))))]])]])]

   ;; Artifact signatures
   (v-signatures/signatures-panel {:signatures signatures :csrf-token csrf-token})])
