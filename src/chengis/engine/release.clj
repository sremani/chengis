(ns chengis.engine.release
  "Release orchestration — create releases from successful builds
   with automatic version suggestion."
  (:require [chengis.db.release-store :as release-store]
            [chengis.db.build-store :as build-store]
            [taoensso.timbre :as log]))

(defn create-release-from-build!
  "Create a release from a successful build. Validates build status.
   Returns {:success true :release row} or {:success false :reason str}."
  [ds {:keys [org-id build-id version title notes created-by]}]
  (let [build (build-store/get-build ds build-id :org-id org-id)]
    (cond
      (nil? build)
      {:success false :reason "Build not found"}

      (not= "success" (name (or (:status build) "")))
      {:success false :reason (str "Build status is " (name (or (:status build) "unknown"))
                                   ", only successful builds can be released")}

      :else
      (let [ver (or version (release-store/suggest-next-version ds org-id (:job-id build)))
            release (release-store/create-release! ds
                      {:org-id org-id
                       :job-id (:job-id build)
                       :build-id build-id
                       :version ver
                       :title (or title (str (:job-id build) " v" ver))
                       :notes notes
                       :created-by created-by})]
        (log/info "Created release" {:id (:id release) :version ver :build-id build-id})
        {:success true :release release}))))

(defn auto-version-release!
  "Create and immediately publish a release with auto-suggested version."
  [ds {:keys [org-id build-id title notes created-by]}]
  (let [result (create-release-from-build! ds
                 {:org-id org-id :build-id build-id
                  :title title :notes notes :created-by created-by})]
    (if (:success result)
      (do (release-store/publish-release! ds (get-in result [:release :id]) :org-id org-id)
          (let [published (release-store/get-release ds (get-in result [:release :id]))]
            {:success true :release published}))
      result)))
