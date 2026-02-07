(require '[chengis.dsl.core :refer [defpipeline stage step parallel sh when-branch]])

(defpipeline example
  {:description "Example Chengis pipeline"}

  (stage "Hello"
    (step "Greet" (sh "echo 'Hello from Chengis!'")))

  (stage "Test"
    (parallel
      (step "Fast" (sh "echo 'fast test' && sleep 0.5"))
      (step "Slow" (sh "echo 'slow test' && sleep 1"))))

  (stage "Done"
    (step "Finish" (sh "echo 'Build complete!'"))))
