(ns chengis.db.template-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.template-store :as template-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-template-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

(deftest create-and-get-template-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create a template and retrieve it"
      (let [id (template-store/create-template! ds
                 {:name "java-library"
                  :description "Standard Java library build"
                  :format "edn"
                  :content "{:stages [{:name \"Build\" :steps [{:name \"Compile\" :run \"mvn compile\"}]}]}"
                  :created-by "admin"})]
        (is (some? id))
        (let [t (template-store/get-template ds id)]
          (is (= "java-library" (:name t)))
          (is (= "Standard Java library build" (:description t)))
          (is (= "edn" (:format t)))
          (is (= 1 (:version t)))
          (is (= "admin" (:created-by t)))
          (is (some? (:created-at t))))))))

(deftest get-template-by-name-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "find template by name"
      (template-store/create-template! ds
        {:name "node-app" :content "{}" :format "edn"})
      (let [t (template-store/get-template-by-name ds "node-app")]
        (is (some? t))
        (is (= "node-app" (:name t)))))

    (testing "returns nil for non-existent name"
      (is (nil? (template-store/get-template-by-name ds "no-such-template"))))))

(deftest list-templates-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list all templates ordered by name"
      (template-store/create-template! ds {:name "c-app" :content "{}" :format "edn"})
      (template-store/create-template! ds {:name "a-app" :content "{}" :format "edn"})
      (template-store/create-template! ds {:name "b-app" :content "{}" :format "edn"})
      (let [templates (template-store/list-templates ds)]
        (is (= 3 (count templates)))
        (is (= ["a-app" "b-app" "c-app"] (mapv :name templates)))))))

(deftest update-template-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "update a template bumps version"
      (let [id (template-store/create-template! ds
                 {:name "updatable" :content "{:v 1}" :format "edn"})]
        (template-store/update-template! ds id
          {:content "{:v 2}" :description "Updated"})
        (let [t (template-store/get-template ds id)]
          (is (= 2 (:version t)))
          (is (= "{:v 2}" (:content t)))
          (is (= "Updated" (:description t))))))))

(deftest delete-template-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "delete a template"
      (let [id (template-store/create-template! ds
                 {:name "deleteme" :content "{}" :format "edn"})]
        (is (= 1 (template-store/count-templates ds)))
        (template-store/delete-template! ds id)
        (is (= 0 (template-store/count-templates ds)))
        (is (nil? (template-store/get-template ds id)))))))

(deftest count-templates-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "count templates"
      (is (= 0 (template-store/count-templates ds)))
      (template-store/create-template! ds {:name "t1" :content "{}" :format "edn"})
      (template-store/create-template! ds {:name "t2" :content "{}" :format "edn"})
      (is (= 2 (template-store/count-templates ds))))))

(deftest unique-name-constraint-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "duplicate name returns nil"
      (let [id1 (template-store/create-template! ds {:name "unique" :content "{}" :format "edn"})
            id2 (template-store/create-template! ds {:name "unique" :content "{:v2 true}" :format "edn"})]
        (is (some? id1))
        (is (nil? id2))))))

;; ---------------------------------------------------------------------------
;; Phase 4 mutation testing remediation: or-fallback defaults
;; ---------------------------------------------------------------------------

(deftest create-template-nil-format-fallback-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create-template! defaults format to 'edn' when nil"
      (let [id (template-store/create-template! ds
                 {:name "no-format"
                  :content "{:stages []}"
                  :format nil
                  :created-by "test"})]
        (is (some? id))
        (let [t (template-store/get-template ds id)]
          (is (= "edn" (:format t)))))))

  (let [ds (conn/create-datasource test-db-path)]
    (testing "create-template! uses explicit format when provided"
      (let [id (template-store/create-template! ds
                 {:name "yaml-template"
                  :content "stages: []"
                  :format "yaml"
                  :created-by "test"})]
        (is (some? id))
        (let [t (template-store/get-template ds id)]
          (is (= "yaml" (:format t))))))))
