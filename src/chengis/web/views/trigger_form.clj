(ns chengis.web.views.trigger-form
  "Renders a parameterized build trigger form as an htmx partial.
   Parameter types: text (default), choice, boolean."
  (:import [java.net URLEncoder]))

(defn- param-input
  "Render a form input for a single parameter definition."
  [param-name param-def]
  (let [name-str (name param-name)
        input-name (str "param_" name-str)
        param-type (keyword (or (:type param-def) :text))
        default-val (str (or (:default param-def) ""))
        description (:description param-def)]
    [:div {:class "flex flex-col gap-1"}
     [:label {:class "text-xs font-medium text-gray-600" :for input-name}
      name-str
      (when description
        [:span {:class "text-gray-400 font-normal ml-1"} (str "— " description)])]
     (case param-type
       :choice
       [:select {:name input-name :id input-name
                 :class "px-3 py-1.5 border rounded text-sm bg-white"}
        (for [choice (:choices param-def)]
          [:option (cond-> {:value choice}
                     (= choice default-val) (assoc :selected "selected"))
           choice])]

       :boolean
       [:div {:class "flex items-center gap-2"}
        [:input (cond-> {:type "checkbox" :name input-name :id input-name
                         :value "true"
                         :class "rounded border-gray-300"}
                  (= true (:default param-def)) (assoc :checked "checked"))]
        [:span {:class "text-sm text-gray-500"}
         (if (:default param-def) "enabled by default" "disabled by default")]]

       ;; default: text input
       [:input {:type "text" :name input-name :id input-name
                :value default-val
                :class "px-3 py-1.5 border rounded text-sm w-64"
                :placeholder (or description name-str)}])]))

(defn render-trigger-form
  "Render the parameterized build trigger form as an htmx partial.
   Parameters is a map of {param-name {:type :text :default \"...\" :description \"...\"}}"
  [job-name parameters csrf-token]
  (let [encoded-name (URLEncoder/encode (str job-name) "UTF-8")]
    [:div {:class "bg-white rounded-lg shadow-lg border p-5 mt-3"
           :id "trigger-form-panel"}
     [:h3 {:class "text-sm font-semibold text-gray-900 mb-3"} "Build Parameters"]
     [:form {:method "POST"
             :action (str "/jobs/" encoded-name "/trigger")
             :class "space-y-3"}
      [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
      (if (seq parameters)
        (for [[param-name param-def] (sort-by (comp str key) parameters)]
          (param-input param-name param-def))
        [:p {:class "text-gray-400 text-sm"} "No parameters defined."])
      [:div {:class "flex items-center gap-3 pt-2 border-t mt-3"}
       [:button {:type "submit"
                 :class "bg-blue-600 text-white px-4 py-1.5 rounded text-sm font-medium
                         hover:bg-blue-700 active:bg-blue-800 transition-colors"}
        "Run Build"]
       [:button {:type "button"
                 :class "text-gray-500 text-sm hover:text-gray-700"
                 :onclick "this.closest('#trigger-form-panel').remove()"}
        "Cancel"]]]]))
