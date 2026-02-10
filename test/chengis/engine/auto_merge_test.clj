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
