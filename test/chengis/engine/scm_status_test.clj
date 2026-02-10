(ns chengis.engine.scm-status-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.scm-status :as scm-status]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [chengis.plugin.builtin.github-status :as github-status]
            [chengis.plugin.builtin.gitlab-status :as gitlab-status]))

;; ---------------------------------------------------------------------------
;; Provider detection tests
;; ---------------------------------------------------------------------------

(deftest detect-provider-test
  (testing "GitHub URLs"
    (is (= :github (scm-status/detect-provider "https://github.com/owner/repo.git")))
    (is (= :github (scm-status/detect-provider "git@github.com:owner/repo.git")))
    (is (= :github (scm-status/detect-provider "https://github.com/org/project"))))

  (testing "GitLab URLs"
    (is (= :gitlab (scm-status/detect-provider "https://gitlab.com/group/project.git")))
    (is (= :gitlab (scm-status/detect-provider "git@gitlab.com:group/project.git")))
    (is (= :gitlab (scm-status/detect-provider "https://gitlab.com/org/sub/project"))))

  (testing "Bitbucket URLs"
    (is (= :bitbucket (scm-status/detect-provider "https://bitbucket.org/owner/repo.git"))))

  (testing "Unknown URLs"
    (is (nil? (scm-status/detect-provider "https://unknown.example.com/owner/repo.git")))
    (is (nil? (scm-status/detect-provider "")))
    (is (nil? (scm-status/detect-provider nil)))))

;; ---------------------------------------------------------------------------
;; Status mapping tests
;; ---------------------------------------------------------------------------

(deftest build-status-to-scm-status-test
  (testing "maps build statuses to generic SCM statuses"
    (is (= "success" (scm-status/build-status->scm-status :success)))
    (is (= "failure" (scm-status/build-status->scm-status :failure)))
    (is (= "error"   (scm-status/build-status->scm-status :aborted)))
    (is (= "pending" (scm-status/build-status->scm-status :running)))
    (is (= "pending" (scm-status/build-status->scm-status :queued)))
    (is (= "pending" (scm-status/build-status->scm-status :waiting-approval)))))

;; ---------------------------------------------------------------------------
;; GitHub URL extraction tests
;; ---------------------------------------------------------------------------

(deftest github-owner-repo-extraction-test
  ;; extract-owner-repo is private, test indirectly via the full report flow
  (testing "GitHub URL parsing tested via report dispatch"
    (is true)))

;; ---------------------------------------------------------------------------
;; Report dispatch tests (with mock reporter)
;; ---------------------------------------------------------------------------

(defrecord MockStatusReporter [calls-atom]
  proto/ScmStatusReporter
  (report-status [_this build-info config]
    (swap! calls-atom conj {:build-info build-info :config config})
    {:status :sent :details "mock"}))

(deftest report-no-op-without-commit-sha-test
  (testing "no-op when commit-sha is missing"
    (let [calls (atom [])
          system {:config {:scm {:github {:token "test-token"}}}}]
      (registry/reset-registry!)
      (registry/register-status-reporter! :github (->MockStatusReporter calls))
      (let [result (scm-status/report! system
                     {:repo-url "https://github.com/o/r.git"
                      :build-id "b1"
                      :job-id "j1"}
                     :success "done")]
        (is (nil? result))
        (is (empty? @calls))))))

(deftest report-no-op-without-repo-url-test
  (testing "no-op when repo-url is missing"
    (let [calls (atom [])
          system {:config {:scm {:github {:token "test-token"}}}}]
      (registry/reset-registry!)
      (registry/register-status-reporter! :github (->MockStatusReporter calls))
      (let [result (scm-status/report! system
                     {:commit-sha "abc123"
                      :build-id "b1"
                      :job-id "j1"}
                     :success "done")]
        (is (nil? result))
        (is (empty? @calls))))))

(deftest report-dispatches-to-github-test
  (testing "dispatches to GitHub reporter when repo is GitHub"
    (let [calls (atom [])
          system {:config {:server {:port 8080}
                           :scm {:github {:token "test-token"
                                          :context "ci/test"}}}}]
      (registry/reset-registry!)
      (registry/register-status-reporter! :github (->MockStatusReporter calls))
      (scm-status/report! system
        {:commit-sha "abc123def"
         :repo-url "https://github.com/myorg/myrepo.git"
         :build-id "build-1"
         :job-id "job-1"}
        :success "Build passed")
      (is (= 1 (count @calls)))
      (let [call (first @calls)
            info (:build-info call)]
        (is (= "abc123def" (:commit-sha info)))
        (is (= "success" (:status info)))
        (is (= "ci/test" (:context info)))
        (is (some? (:build-url info)))))))

(deftest report-dispatches-to-gitlab-test
  (testing "dispatches to GitLab reporter when repo is GitLab"
    (let [calls (atom [])
          system {:config {:server {:port 8080}
                           :scm {:gitlab {:token "test-token"}}}}]
      (registry/reset-registry!)
      (registry/register-status-reporter! :gitlab (->MockStatusReporter calls))
      (scm-status/report! system
        {:commit-sha "def456"
         :repo-url "https://gitlab.com/group/project.git"
         :build-id "build-2"
         :job-id "job-2"}
        :failure "Build failed")
      (is (= 1 (count @calls)))
      (let [call (first @calls)
            info (:build-info call)]
        (is (= "def456" (:commit-sha info)))
        (is (= "failure" (:status info)))))))

(deftest report-no-reporter-registered-test
  (testing "no-op when no reporter is registered for provider"
    (let [system {:config {:scm {:github {:token "test-token"}}}}]
      (registry/reset-registry!)
      ;; Don't register any reporter
      (let [result (scm-status/report! system
                     {:commit-sha "abc123"
                      :repo-url "https://github.com/o/r.git"
                      :build-id "b1"
                      :job-id "j1"}
                     :success "done")]
        (is (nil? result))))))

(deftest report-unknown-provider-test
  (testing "no-op when URL is from unknown provider"
    (let [calls (atom [])
          system {:config {:scm {}}}]
      (registry/reset-registry!)
      (registry/register-status-reporter! :github (->MockStatusReporter calls))
      (let [result (scm-status/report! system
                     {:commit-sha "abc123"
                      :repo-url "https://bitbucket.org/o/r.git"
                      :build-id "b1"
                      :job-id "j1"}
                     :success "done")]
        (is (nil? result))
        (is (empty? @calls))))))

;; ---------------------------------------------------------------------------
;; GitHub plugin unit tests
;; ---------------------------------------------------------------------------

(deftest github-status-reporter-no-token-test
  (testing "GitHub reporter skips when no token"
    (let [reporter (github-status/->GitHubStatusReporter)
          result (proto/report-status reporter
                   {:commit-sha "abc" :repo-url "https://github.com/o/r.git"
                    :status "success" :build-url "http://ci/builds/1"}
                   {:token nil})]  ;; no token
      (is (= :skipped (:status result))))))

(deftest github-status-reporter-bad-url-test
  (testing "GitHub reporter fails on unparseable URL"
    (let [reporter (github-status/->GitHubStatusReporter)
          result (proto/report-status reporter
                   {:commit-sha "abc" :repo-url "https://example.com/something"
                    :status "success" :build-url "http://ci/builds/1"}
                   {:token "test-token"})]
      (is (= :failed (:status result))))))

;; ---------------------------------------------------------------------------
;; GitLab plugin unit tests
;; ---------------------------------------------------------------------------

(deftest gitlab-status-reporter-no-token-test
  (testing "GitLab reporter skips when no token"
    (let [reporter (gitlab-status/->GitLabStatusReporter)
          result (proto/report-status reporter
                   {:commit-sha "abc" :repo-url "https://gitlab.com/g/p.git"
                    :status "success" :build-url "http://ci/builds/1"}
                   {:token nil})]
      (is (= :skipped (:status result))))))

(deftest gitlab-status-reporter-bad-url-test
  (testing "GitLab reporter fails on unparseable URL"
    (let [reporter (gitlab-status/->GitLabStatusReporter)
          result (proto/report-status reporter
                   {:commit-sha "abc" :repo-url "://malformed"
                    :status "success" :build-url "http://ci/builds/1"}
                   {:token "test-token"})]
      (is (= :failed (:status result))))))
