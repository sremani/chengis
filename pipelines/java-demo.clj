(require '[chengis.dsl.core :refer [defpipeline stage step sh artifacts]])

(defpipeline java-demo
  {:description "Java CI — JUnit5 Samples (Maven)"
   :source {:type :git
            :url "https://github.com/junit-team/junit5-samples.git"
            :branch "main"
            :depth 1}}

  (stage "Build"
    (step "Compile"
      (sh "cd junit-jupiter-starter-maven && mvn compile -q")))

  (stage "Test"
    (step "Unit Tests"
      (sh "cd junit-jupiter-starter-maven && mvn test")))

  (artifacts "junit-jupiter-starter-maven/target/surefire-reports/*.xml"))
