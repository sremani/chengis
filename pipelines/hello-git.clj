(require '[chengis.dsl.core :refer [defpipeline stage step sh]])

(defpipeline hello-git
  {:description "Test pipeline — clones a public repo and verifies git info"
   :source {:type :git
            :url "https://github.com/octocat/Hello-World.git"
            :branch "master"
            :depth 1}}

  (stage "Verify"
    (step "Check files"
      (sh "ls -la"))
    (step "Git info"
      (sh "echo Branch=$GIT_BRANCH Commit=$GIT_COMMIT_SHORT Author=$GIT_AUTHOR"))
    (step "Git log"
      (sh "git log --oneline -3"))))
