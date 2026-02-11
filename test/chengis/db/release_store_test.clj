(ns chengis.db.release-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.release-store :as release-store]))

(def test-db-path "/tmp/chengis-release-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

(deftest create-release-test
  (let [ds (test-ds)]
    (testing "create-release! returns release with correct fields"
      (let [r (release-store/create-release! ds
                {:org-id "org-1" :job-id "job-1" :build-id "build-1"
                 :version "1.0.0" :title "First Release" :notes "Initial"
                 :created-by "user-1"})]
        (is (some? (:id r)))
        (is (= "org-1" (:org-id r)))
        (is (= "1.0.0" (:version r)))
        (is (= "draft" (:status r)))))

    (testing "create with explicit status"
      (let [r (release-store/create-release! ds
                {:org-id "org-1" :job-id "job-1" :build-id "build-2"
                 :version "1.0.1" :status "published"})]
        (is (= "published" (:status r)))))))

(deftest get-release-test
  (let [ds (test-ds)]
    (testing "get-release retrieves created release"
      (let [created (release-store/create-release! ds
                      {:org-id "org-1" :job-id "job-1" :build-id "b-1" :version "1.0.0"})
            retrieved (release-store/get-release ds (:id created))]
        (is (some? retrieved))
        (is (= (:id created) (:id retrieved)))))

    (testing "get-release with org-id scoping"
      (let [created (release-store/create-release! ds
                      {:org-id "org-2" :job-id "job-1" :build-id "b-2" :version "1.0.0"})]
        (is (some? (release-store/get-release ds (:id created) :org-id "org-2")))
        (is (nil? (release-store/get-release ds (:id created) :org-id "org-other")))))

    (testing "get-release returns nil for non-existent"
      (is (nil? (release-store/get-release ds "non-existent"))))))

(deftest list-releases-test
  (let [ds (test-ds)]
    (testing "list-releases returns releases for org"
      (release-store/create-release! ds {:org-id "org-1" :job-id "j1" :build-id "b1" :version "1.0.0"})
      (release-store/create-release! ds {:org-id "org-1" :job-id "j1" :build-id "b2" :version "1.0.1"})
      (release-store/create-release! ds {:org-id "org-2" :job-id "j1" :build-id "b3" :version "1.0.0"})
      (is (= 2 (count (release-store/list-releases ds :org-id "org-1"))))
      (is (= 1 (count (release-store/list-releases ds :org-id "org-2")))))

    (testing "list-releases filters by status"
      (release-store/create-release! ds {:org-id "org-1" :job-id "j2" :build-id "b4" :version "2.0.0" :status "published"})
      (is (= 1 (count (release-store/list-releases ds :org-id "org-1" :status "published")))))))

(deftest update-release-test
  (let [ds (test-ds)]
    (testing "update-release! updates fields"
      (let [r (release-store/create-release! ds
                {:org-id "org-1" :job-id "j1" :build-id "b1" :version "1.0.0"})
            cnt (release-store/update-release! ds (:id r) {:title "Updated Title" :notes "New notes"})]
        (is (= 1 cnt))
        (let [updated (release-store/get-release ds (:id r))]
          (is (= "Updated Title" (:title updated))))))

    (testing "update-release! with org-id scoping"
      (let [r (release-store/create-release! ds
                {:org-id "org-1" :job-id "j1" :build-id "b2" :version "1.0.2"})]
        (is (= 0 (release-store/update-release! ds (:id r) {:title "X"} :org-id "wrong-org")))))))

(deftest publish-release-test
  (let [ds (test-ds)]
    (testing "publish-release! transitions draft to published"
      (let [r (release-store/create-release! ds
                {:org-id "org-1" :job-id "j1" :build-id "b1" :version "1.0.0"})]
        (is (true? (release-store/publish-release! ds (:id r))))
        (let [published (release-store/get-release ds (:id r))]
          (is (= "published" (:status published)))
          (is (some? (:published-at published))))))

    (testing "publish-release! fails for non-draft"
      (let [r (release-store/create-release! ds
                {:org-id "org-1" :job-id "j1" :build-id "b2" :version "1.0.1" :status "published"})]
        (is (false? (release-store/publish-release! ds (:id r))))))))

(deftest deprecate-release-test
  (let [ds (test-ds)]
    (testing "deprecate-release! transitions published to deprecated"
      (let [r (release-store/create-release! ds
                {:org-id "org-1" :job-id "j1" :build-id "b1" :version "1.0.0"})]
        (release-store/publish-release! ds (:id r))
        (is (true? (release-store/deprecate-release! ds (:id r))))
        (let [deprecated (release-store/get-release ds (:id r))]
          (is (= "deprecated" (:status deprecated)))
          (is (some? (:deprecated-at deprecated))))))

    (testing "deprecate-release! fails for draft"
      (let [r (release-store/create-release! ds
                {:org-id "org-1" :job-id "j1" :build-id "b2" :version "1.0.2"})]
        (is (false? (release-store/deprecate-release! ds (:id r))))))))

(deftest get-latest-release-test
  (let [ds (test-ds)]
    (testing "get-latest-release returns a published release"
      (let [r1 (release-store/create-release! ds
                 {:org-id "org-1" :job-id "j1" :build-id "b1" :version "1.0.0"})
            r2 (release-store/create-release! ds
                 {:org-id "org-1" :job-id "j1" :build-id "b2" :version "1.0.1"})]
        (release-store/publish-release! ds (:id r1))
        (release-store/publish-release! ds (:id r2))
        (let [latest (release-store/get-latest-release ds "org-1" "j1")]
          (is (some? latest))
          (is (= "published" (:status latest)))
          (is (contains? #{"1.0.0" "1.0.1"} (:version latest))))))

    (testing "get-latest-release returns nil when no published"
      (is (nil? (release-store/get-latest-release ds "org-1" "no-job"))))))

(deftest suggest-next-version-test
  (let [ds (test-ds)]
    (testing "suggest-next-version increments patch"
      (release-store/create-release! ds
        {:org-id "org-1" :job-id "j1" :build-id "b1" :version "1.2.3"})
      (is (= "1.2.4" (release-store/suggest-next-version ds "org-1" "j1"))))

    (testing "suggest-next-version falls back to 1.0.N"
      (is (= "1.0.0" (release-store/suggest-next-version ds "org-1" "new-job"))))))

(deftest delete-release-test
  (let [ds (test-ds)]
    (testing "delete-release! removes release"
      (let [r (release-store/create-release! ds
                {:org-id "org-1" :job-id "j1" :build-id "b1" :version "1.0.0"})]
        (is (true? (release-store/delete-release! ds (:id r))))
        (is (nil? (release-store/get-release ds (:id r))))))

    (testing "delete-release! with org-id scoping"
      (let [r (release-store/create-release! ds
                {:org-id "org-1" :job-id "j1" :build-id "b2" :version "1.0.1"})]
        (is (false? (release-store/delete-release! ds (:id r) :org-id "wrong-org")))
        (is (true? (release-store/delete-release! ds (:id r) :org-id "org-1")))))))

(deftest version-uniqueness-test
  (let [ds (test-ds)]
    (testing "duplicate version within same org+job throws"
      (release-store/create-release! ds
        {:org-id "org-1" :job-id "j1" :build-id "b1" :version "1.0.0"})
      (is (thrown? Exception
            (release-store/create-release! ds
              {:org-id "org-1" :job-id "j1" :build-id "b2" :version "1.0.0"}))))))
