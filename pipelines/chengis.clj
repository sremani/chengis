(require '[chengis.dsl.core :refer [defpipeline stage step sh]])

(defpipeline chengis
  {:description "Self-hosting pipeline — Chengis builds itself"}

  (stage "Checkout"
    (step "Copy source"
      (sh "cp -r /Users/sremani/chengis/src /Users/sremani/chengis/project.clj /Users/sremani/chengis/resources /Users/sremani/chengis/test /Users/sremani/chengis/pipelines .")))

  (stage "Test"
    (step "Run tests"
      (sh "lein test" :timeout 120000)))

  (stage "Build"
    (step "Uberjar"
      (sh "lein uberjar" :timeout 300000)))

  (stage "Verify"
    (step "Check artifact"
      (sh "ls -lh target/uberjar/chengis-*-standalone.jar"))
    (step "Smoke test"
      (sh "java -jar target/uberjar/chengis-*-standalone.jar 2>&1 | head -5; exit 0" :timeout 30000))))
