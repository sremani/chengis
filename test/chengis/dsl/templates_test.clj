(ns chengis.dsl.templates-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.template-store :as template-store]
            [chengis.dsl.templates :as templates]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-templates-dsl-test.db")

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

(deftest no-extends-passthrough-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "pipeline without extends passes through unchanged"
      (let [pipeline {:stages [{:stage-name "Build" :steps [{:step-name "Compile"}]}]}
            result (templates/resolve-extends ds pipeline)]
        (is (= pipeline result))))))

(deftest simple-extends-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "pipeline extends a template"
      ;; Create base template
      (template-store/create-template! ds
        {:name "java-base"
         :format "edn"
         :content (pr-str {:stages [{:stage-name "Build"
                                     :steps [{:step-name "Compile" :command "mvn compile"}]}
                                    {:stage-name "Test"
                                     :steps [{:step-name "Unit" :command "mvn test"}]}]
                           :env {"JAVA_HOME" "/usr/lib/jvm/java-17"}})})

      ;; Pipeline that extends the template
      (let [pipeline {:extends "java-base"
                      :stages [{:stage-name "Deploy"
                                :steps [{:step-name "Ship" :command "./deploy.sh"}]}]}
            result (templates/resolve-extends ds pipeline)]
        ;; Should have 3 stages: Build + Test from template + Deploy from pipeline
        (is (= 3 (count (:stages result))))
        (is (= "Build" (:stage-name (first (:stages result)))))
        (is (= "Test" (:stage-name (second (:stages result)))))
        (is (= "Deploy" (:stage-name (nth (:stages result) 2))))
        ;; Env from template should be present
        (is (= {"JAVA_HOME" "/usr/lib/jvm/java-17"} (:env result)))
        ;; No :extends key in result
        (is (nil? (:extends result)))))))

(deftest stage-override-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "pipeline stage overrides template stage by name"
      (template-store/create-template! ds
        {:name "build-base"
         :format "edn"
         :content (pr-str {:stages [{:stage-name "Build"
                                     :steps [{:step-name "Compile" :command "make"}]}
                                    {:stage-name "Test"
                                     :steps [{:step-name "Run" :command "make test"}]}]})})

      (let [pipeline {:extends "build-base"
                      :stages [{:stage-name "Build"
                                :steps [{:step-name "Compile" :command "gradle build"}]}]}
            result (templates/resolve-extends ds pipeline)]
        ;; Should have 2 stages: overridden Build + original Test
        (is (= 2 (count (:stages result))))
        (is (= "gradle build" (get-in (first (:stages result)) [:steps 0 :command])))
        (is (= "Run" (get-in (second (:stages result)) [:steps 0 :step-name])))))))

(deftest env-merge-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "env maps are merged, pipeline wins"
      (template-store/create-template! ds
        {:name "env-base"
         :format "edn"
         :content (pr-str {:stages [{:stage-name "S1" :steps [{:step-name "S" :command "echo"}]}]
                           :env {"A" "1" "B" "2"}})})

      (let [pipeline {:extends "env-base"
                      :env {"B" "override" "C" "3"}
                      :stages []}
            result (templates/resolve-extends ds pipeline)]
        (is (= {"A" "1" "B" "override" "C" "3"} (:env result)))))))

(deftest missing-template-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "missing template logs warning and returns pipeline without extends"
      (let [pipeline {:extends "non-existent"
                      :stages [{:stage-name "Build" :steps []}]}
            result (templates/resolve-extends ds pipeline)]
        (is (= 1 (count (:stages result))))
        (is (nil? (:extends result)))))))

(deftest cycle-detection-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "template cycle is detected and stopped"
      ;; Create two templates that extend each other
      (template-store/create-template! ds
        {:name "cycle-a"
         :format "edn"
         :content (pr-str {:extends "cycle-b"
                           :stages [{:stage-name "A" :steps [{:step-name "a" :command "a"}]}]})})
      (template-store/create-template! ds
        {:name "cycle-b"
         :format "edn"
         :content (pr-str {:extends "cycle-a"
                           :stages [{:stage-name "B" :steps [{:step-name "b" :command "b"}]}]})})

      (let [pipeline {:extends "cycle-a"
                      :stages [{:stage-name "C" :steps [{:step-name "c" :command "c"}]}]}
            result (templates/resolve-extends ds pipeline)]
        ;; Should resolve without infinite loop
        (is (some? result))
        (is (nil? (:extends result)))))))

(deftest depth-limit-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "resolution stops at max depth"
      ;; Create chain: d3 -> d2 -> d1 -> d0
      (template-store/create-template! ds
        {:name "d0" :format "edn"
         :content (pr-str {:stages [{:stage-name "D0" :steps [{:step-name "s" :command "c"}]}]})})
      (template-store/create-template! ds
        {:name "d1" :format "edn"
         :content (pr-str {:extends "d0" :stages [{:stage-name "D1" :steps [{:step-name "s" :command "c"}]}]})})
      (template-store/create-template! ds
        {:name "d2" :format "edn"
         :content (pr-str {:extends "d1" :stages [{:stage-name "D2" :steps [{:step-name "s" :command "c"}]}]})})
      (template-store/create-template! ds
        {:name "d3" :format "edn"
         :content (pr-str {:extends "d2" :stages [{:stage-name "D3" :steps [{:step-name "s" :command "c"}]}]})})

      ;; With max-depth 2, should stop at d2 and not resolve d1->d0
      (let [pipeline {:extends "d3" :stages []}
            result (templates/resolve-extends ds pipeline {:max-depth 2})]
        (is (some? result))
        (is (nil? (:extends result)))))))

(deftest chained-extends-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "chained template inheritance works"
      (template-store/create-template! ds
        {:name "base" :format "edn"
         :content (pr-str {:stages [{:stage-name "Build" :steps [{:step-name "Compile" :command "gcc"}]}]
                           :env {"CC" "gcc"}})})
      (template-store/create-template! ds
        {:name "middle" :format "edn"
         :content (pr-str {:extends "base"
                           :stages [{:stage-name "Test" :steps [{:step-name "Run" :command "ctest"}]}]
                           :env {"CC" "g++"}})})

      (let [pipeline {:extends "middle"
                      :stages [{:stage-name "Deploy" :steps [{:step-name "Ship" :command "deploy"}]}]}
            result (templates/resolve-extends ds pipeline)]
        ;; Should have stages from base (Build), middle (Test), and pipeline (Deploy)
        (is (>= (count (:stages result)) 3))
        ;; Middle overrides base env
        (is (= "g++" (get-in result [:env "CC"])))
        (is (nil? (:extends result)))))))

(deftest artifacts-merge-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "artifacts are merged (union)"
      (template-store/create-template! ds
        {:name "art-base" :format "edn"
         :content (pr-str {:stages [{:stage-name "B" :steps [{:step-name "s" :command "c"}]}]
                           :artifacts ["target/*.jar"]})})

      (let [pipeline {:extends "art-base"
                      :artifacts ["dist/*.zip"]
                      :stages []}
            result (templates/resolve-extends ds pipeline)]
        (is (= 2 (count (:artifacts result))))
        (is (some #{"target/*.jar"} (:artifacts result)))
        (is (some #{"dist/*.zip"} (:artifacts result)))))))
