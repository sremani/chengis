(ns chengis.engine.auto-merge-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.pr-check-store :as pr-store]
            [chengis.engine.auto-merge :as auto-merge]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-auto-merge-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; should-auto-merge? tests
;; ---------------------------------------------------------------------------

(deftest should-auto-merge-all-conditions
  (testing "returns true when all conditions met"
    (let [system {:config {:feature-flags {:auto-merge true}}}
          build-info {:pr-number 42}
          job {:auto-merge-enabled true}]
      (is (true? (auto-merge/should-auto-merge? system build-info job))))))

(deftest should-auto-merge-flag-disabled
  (testing "returns false when feature flag disabled"
    (let [system {:config {:feature-flags {:auto-merge false}}}
          build-info {:pr-number 42}
          job {:auto-merge-enabled true}]
      (is (false? (auto-merge/should-auto-merge? system build-info job))))))

(deftest should-auto-merge-job-disabled
  (testing "returns false when job auto-merge disabled"
    (let [system {:config {:feature-flags {:auto-merge true}}}
          build-info {:pr-number 42}
          job {:auto-merge-enabled false}]
      (is (false? (auto-merge/should-auto-merge? system build-info job))))))

(deftest should-auto-merge-no-pr-number
  (testing "returns false when no PR number"
    (let [system {:config {:feature-flags {:auto-merge true}}}
          build-info {}
          job {:auto-merge-enabled true}]
      (is (false? (auto-merge/should-auto-merge? system build-info job))))))

(deftest should-auto-merge-mr-number
  (testing "works with GitLab merge-request-number"
    (let [system {:config {:feature-flags {:auto-merge true}}}
          build-info {:merge-request-number 7}
          job {:auto-merge-enabled true}]
      (is (true? (auto-merge/should-auto-merge? system build-info job))))))

;; ---------------------------------------------------------------------------
;; evaluate-and-merge! tests
;; ---------------------------------------------------------------------------

(deftest evaluate-feature-flag-disabled
  (testing "returns skipped when feature flag disabled"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds :config {:feature-flags {:auto-merge false}}}
          result (auto-merge/evaluate-and-merge! system {:build-id "b1"} {:auto-merge-enabled true})]
      (is (= :skipped (:status result))))))

(deftest evaluate-job-not-enabled
  (testing "returns skipped when job auto-merge not enabled"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds :config {:feature-flags {:auto-merge true}}}
          result (auto-merge/evaluate-and-merge! system {:build-id "b1"} {:auto-merge-enabled false})]
      (is (= :skipped (:status result))))))

(deftest evaluate-no-pr-number
  (testing "returns no-pr when build has no PR number"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds :config {:feature-flags {:auto-merge true}}}
          result (auto-merge/evaluate-and-merge! system {:build-id "b1" :job-id "j1"} {:auto-merge-enabled true})]
      (is (= :no-pr (:status result))))))

(deftest evaluate-checks-not-passing
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:auto-merge true
                                                 :pr-status-checks true}}}]
    (testing "returns not-ready when required checks not passing"
      ;; Set up a required check
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "build" :required true})
      ;; Record a failing check result
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "build" :status :failure})

      (let [result (auto-merge/evaluate-and-merge! system
                     {:build-id "b1" :job-id "job-1" :pr-number 42
                      :repo-url "https://github.com/o/r"} {:auto-merge-enabled true})]
        (is (= :not-ready (:status result)))))))

(deftest evaluate-all-checks-passing-triggers-merge
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:auto-merge true}
                                :auto-merge {:merge-method "merge"}
                                :scm {:github {:token "test-token"}}}}
        merge-called (atom false)]
    (testing "triggers merge when all checks pass"
      ;; Set up required check
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "build" :required true})
      ;; Record passing check
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "build" :status :success})

      ;; Mock GitHub merge API
      (with-redefs [org.httpkit.client/put
                    (fn [_url _opts]
                      (reset! merge-called true)
                      (deliver (promise) {:status 200 :body "{\"merged\":true}"}))]
        (let [result (auto-merge/evaluate-and-merge! system
                       {:build-id "b1" :job-id "job-1" :pr-number 42
                        :repo-url "https://github.com/owner/repo"} {:auto-merge-enabled true})]
          (is (true? @merge-called))
          (is (= :merged (:status result))))))))

(deftest evaluate-merge-api-failure
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:auto-merge true}
                                :auto-merge {:merge-method "merge"}
                                :scm {:github {:token "test-token"}}}}]
    (testing "returns failed when merge API returns error"
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "build" :status :success})

      (with-redefs [org.httpkit.client/put
                    (fn [_url _opts]
                      (deliver (promise) {:status 405 :body "Method not allowed"}))]
        (let [result (auto-merge/evaluate-and-merge! system
                       {:build-id "b1" :job-id "job-1" :pr-number 42
                        :repo-url "https://github.com/o/r"} {:auto-merge-enabled true})]
          (is (= :failed (:status result))))))))

(deftest evaluate-unknown-provider
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:auto-merge true}
                                :auto-merge {:merge-method "merge"}}}]
    (testing "returns failed for unknown SCM provider"
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "build" :status :success})

      (let [result (auto-merge/evaluate-and-merge! system
                     {:build-id "b1" :job-id "job-1" :pr-number 42
                      :repo-url "https://unknown.example.com/o/r"} {:auto-merge-enabled true})]
        (is (= :failed (:status result)))))))

(deftest evaluate-gitlab-merge
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:auto-merge true}
                                :auto-merge {:merge-method "squash"
                                             :delete-branch-after true}
                                :scm {:gitlab {:token "gitlab-token"}}}}
        captured-url (atom nil)
        captured-body (atom nil)]
    (testing "calls GitLab merge API with correct params"
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "build" :status :success})

      (with-redefs [org.httpkit.client/put
                    (fn [url opts]
                      (reset! captured-url url)
                      (reset! captured-body (clojure.data.json/read-str (:body opts) :key-fn keyword))
                      (deliver (promise) {:status 200 :body "{}"}))]
        (auto-merge/evaluate-and-merge! system
          {:build-id "b1" :job-id "job-1" :merge-request-number 7
           :repo-url "https://gitlab.com/mygroup/myrepo.git"} {:auto-merge-enabled true})
        (is (re-find #"merge_requests/7/merge" @captured-url))
        (is (true? (:squash @captured-body)))
        (is (true? (:should_remove_source_branch @captured-body)))))))

(deftest evaluate-bitbucket-merge
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:auto-merge true}
                                :auto-merge {:merge-method "merge"}
                                :scm {:bitbucket {:username "user" :app-password "pass"}}}}
        merge-called (atom false)]
    (testing "calls Bitbucket merge API"
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "build" :status :success})

      (with-redefs [org.httpkit.client/post
                    (fn [url _opts]
                      (reset! merge-called true)
                      (deliver (promise) {:status 200 :body "{}"}))]
        (let [result (auto-merge/evaluate-and-merge! system
                       {:build-id "b1" :job-id "job-1" :pr-number 99
                        :repo-url "https://bitbucket.org/ws/repo"} {:auto-merge-enabled true})]
          (is (true? @merge-called))
          (is (= :merged (:status result))))))))

(deftest evaluate-no-required-checks-returns-not-ready
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:auto-merge true}
                                :auto-merge {:merge-method "merge"}
                                :scm {:github {:token "tok"}}}}]
    (testing "when no required checks are configured, returns not-ready (no checks to satisfy)"
      ;; No checks configured — all-required-checks-passing? returns passing? false
      ;; because (pos? 0) is false — by design, no checks = not ready
      (let [result (auto-merge/evaluate-and-merge! system
                     {:build-id "b1" :job-id "job-1" :pr-number 42
                      :repo-url "https://github.com/o/r"} {:auto-merge-enabled true})]
        (is (= :not-ready (:status result)))))))

;; ---------------------------------------------------------------------------
;; Phase 2a: HTTP status boundary tests for merge functions
;; Phase 2d: or-fallback tests for config defaults
;; ---------------------------------------------------------------------------

(deftest github-merge-status-boundary-test
  (testing "HTTP 299 is treated as success (< 300)"
    (with-redefs [org.httpkit.client/put
                  (fn [_url _opts]
                    (let [p (promise)] (deliver p {:status 299 :body "{}"}) p))]
      (let [result (#'auto-merge/merge-github-pr! "o" "r" 1
                      {:token "tok"} {:merge-method "merge"})]
        (is (= :merged (:status result))))))

  (testing "HTTP 300 is treated as failure (not < 300)"
    (with-redefs [org.httpkit.client/put
                  (fn [_url _opts]
                    (let [p (promise)] (deliver p {:status 300 :body "{}"}) p))]
      (let [result (#'auto-merge/merge-github-pr! "o" "r" 1
                      {:token "tok"} {:merge-method "merge"})]
        (is (= :failed (:status result)))))))

(deftest gitlab-merge-status-boundary-test
  (testing "HTTP 299 is success for GitLab merge"
    (with-redefs [org.httpkit.client/put
                  (fn [_url _opts]
                    (let [p (promise)] (deliver p {:status 299 :body "{}"}) p))]
      (let [result (#'auto-merge/merge-gitlab-mr! "group/repo" 1
                      {:token "tok"} {})]
        (is (= :merged (:status result))))))

  (testing "HTTP 300 is failure for GitLab merge"
    (with-redefs [org.httpkit.client/put
                  (fn [_url _opts]
                    (let [p (promise)] (deliver p {:status 300 :body "{}"}) p))]
      (let [result (#'auto-merge/merge-gitlab-mr! "group/repo" 1
                      {:token "tok"} {})]
        (is (= :failed (:status result)))))))

(deftest bitbucket-merge-status-boundary-test
  (testing "HTTP 299 is success for Bitbucket merge"
    (with-redefs [org.httpkit.client/post
                  (fn [_url _opts]
                    (let [p (promise)] (deliver p {:status 299 :body "{}"}) p))]
      (let [result (#'auto-merge/merge-bitbucket-pr! "ws" "r" 1
                      {:username "u" :app-password "p"} {})]
        (is (= :merged (:status result))))))

  (testing "HTTP 300 is failure for Bitbucket merge"
    (with-redefs [org.httpkit.client/post
                  (fn [_url _opts]
                    (let [p (promise)] (deliver p {:status 300 :body "{}"}) p))]
      (let [result (#'auto-merge/merge-bitbucket-pr! "ws" "r" 1
                      {:username "u" :app-password "p"} {})]
        (is (= :failed (:status result)))))))

(deftest gitea-merge-status-boundary-test
  (testing "HTTP 299 is success for Gitea merge"
    (with-redefs [org.httpkit.client/post
                  (fn [_url _opts]
                    (let [p (promise)] (deliver p {:status 299 :body "{}"}) p))]
      (let [result (#'auto-merge/merge-gitea-pr! "o" "r" 1
                      {:token "tok" :base-url "https://gitea.example.com"} {})]
        (is (= :merged (:status result))))))

  (testing "HTTP 300 is failure for Gitea merge"
    (with-redefs [org.httpkit.client/post
                  (fn [_url _opts]
                    (let [p (promise)] (deliver p {:status 300 :body "{}"}) p))]
      (let [result (#'auto-merge/merge-gitea-pr! "o" "r" 1
                      {:token "tok" :base-url "https://gitea.example.com"} {})]
        (is (= :failed (:status result)))))))

(deftest merge-config-or-fallback-defaults-test
  (testing "merge-method defaults to 'merge' when not specified"
    (with-redefs [org.httpkit.client/put
                  (fn [_url opts]
                    (let [body (clojure.data.json/read-str (:body opts) :key-fn keyword)
                          p (promise)]
                      (is (= "merge" (:merge_method body)))
                      (deliver p {:status 200 :body "{}"})
                      p))]
      (#'auto-merge/merge-github-pr! "o" "r" 1 {:token "tok"} {})))

  (testing "base-url defaults to https://api.github.com"
    (with-redefs [org.httpkit.client/put
                  (fn [url _opts]
                    (is (clojure.string/starts-with? url "https://api.github.com"))
                    (let [p (promise)] (deliver p {:status 200 :body "{}"}) p))]
      (#'auto-merge/merge-github-pr! "o" "r" 1 {:token "tok"} {}))))

;; ---------------------------------------------------------------------------
;; Phase 3d: Auto-merge helper functions and branch deletion tests
;; ---------------------------------------------------------------------------

(deftest extract-owner-repo-https-test
  (testing "extracts owner/repo from HTTPS URLs"
    (let [result (#'auto-merge/extract-owner-repo
                   "https://github.com/acme/myrepo.git")]
      (is (= "acme" (:owner result)))
      (is (= "myrepo" (:repo result))))))

(deftest extract-owner-repo-ssh-test
  (testing "extracts owner/repo from SSH URLs"
    (let [result (#'auto-merge/extract-owner-repo
                   "git@github.com:acme/myrepo.git")]
      (is (= "acme" (:owner result)))
      (is (= "myrepo" (:repo result))))))

(deftest extract-owner-repo-no-git-suffix-test
  (testing "extracts owner/repo without .git suffix"
    (let [result (#'auto-merge/extract-owner-repo
                   "https://github.com/acme/myrepo")]
      (is (= "acme" (:owner result)))
      (is (= "myrepo" (:repo result))))))

(deftest extract-owner-repo-nil-returns-nil-test
  (testing "nil URL returns nil"
    (is (nil? (#'auto-merge/extract-owner-repo nil)))))

(deftest extract-pr-number-test
  (testing "extracts :pr-number when present"
    (is (= 42 (#'auto-merge/extract-pr-number {:pr-number 42}))))
  (testing "falls back to :merge-request-number for GitLab"
    (is (= 7 (#'auto-merge/extract-pr-number {:merge-request-number 7}))))
  (testing "returns nil when neither present"
    (is (nil? (#'auto-merge/extract-pr-number {})))))

(deftest delete-github-branch-success-test
  (testing "branch deletion succeeds with HTTP < 300"
    (with-redefs [org.httpkit.client/delete
                  (fn [url _opts]
                    (is (clojure.string/includes? url "/git/refs/heads/feature-branch"))
                    (let [p (promise)]
                      (deliver p {:status 204 :body ""})
                      p))]
      ;; Should not throw — just logs on success
      (#'auto-merge/delete-github-branch! "o" "r" "feature-branch"
        {:token "tok"}))))

(deftest delete-github-branch-failure-no-throw-test
  (testing "branch deletion failure logs warning but does not throw"
    (with-redefs [org.httpkit.client/delete
                  (fn [_url _opts]
                    (let [p (promise)]
                      (deliver p {:status 422 :body "not found"})
                      p))]
      ;; Should not throw even on failure
      (#'auto-merge/delete-github-branch! "o" "r" "gone-branch"
        {:token "tok"}))))

(deftest gitlab-squash-merge-method-test
  (testing "GitLab squash method sets squash=true in request body"
    (with-redefs [org.httpkit.client/put
                  (fn [_url opts]
                    (let [body (clojure.data.json/read-str (:body opts) :key-fn keyword)
                          p (promise)]
                      (is (true? (:squash body))
                          "squash merge should set squash=true")
                      (deliver p {:status 200 :body "{}"})
                      p))]
      (#'auto-merge/merge-gitlab-mr! "group/repo" 1
        {:token "tok"} {:merge-method "squash"}))))

(deftest gitlab-delete-branch-after-merge-test
  (testing "GitLab delete-branch-after sets should_remove_source_branch"
    (with-redefs [org.httpkit.client/put
                  (fn [_url opts]
                    (let [body (clojure.data.json/read-str (:body opts) :key-fn keyword)
                          p (promise)]
                      (is (true? (:should_remove_source_branch body)))
                      (deliver p {:status 200 :body "{}"})
                      p))]
      (#'auto-merge/merge-gitlab-mr! "group/repo" 1
        {:token "tok"} {:delete-branch-after true}))))

(deftest bitbucket-merge-strategy-mapping-test
  (testing "Bitbucket maps 'rebase' to 'fast_forward' strategy"
    (with-redefs [org.httpkit.client/post
                  (fn [_url opts]
                    (let [body (clojure.data.json/read-str (:body opts) :key-fn keyword)
                          p (promise)]
                      (is (= "fast_forward" (:merge_strategy body)))
                      (deliver p {:status 200 :body "{}"})
                      p))]
      (#'auto-merge/merge-bitbucket-pr! "ws" "r" 1
        {:username "u" :app-password "p"} {:merge-method "rebase"}))))

(deftest extract-project-path-gitlab-test
  (testing "extracts GitLab project path from HTTPS URL"
    (let [result (#'auto-merge/extract-project-path
                   "https://gitlab.com/mygroup/myrepo.git")]
      (is (= "mygroup/myrepo" result)
          "Should extract 'mygroup/myrepo' from GitLab HTTPS URL")))
  (testing "extracts project path without .git suffix"
    (let [result (#'auto-merge/extract-project-path
                   "https://gitlab.com/mygroup/myrepo")]
      (is (= "mygroup/myrepo" result))))
  (testing "nil URL returns nil"
    (is (nil? (#'auto-merge/extract-project-path nil)))))
