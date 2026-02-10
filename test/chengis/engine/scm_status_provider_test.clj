(ns chengis.engine.scm-status-provider-test
  "Tests for SCM provider detection with Gitea and Bitbucket support."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.scm-status :as scm]))

;; ---------------------------------------------------------------------------
;; Provider detection — existing providers
;; ---------------------------------------------------------------------------

(deftest detect-github-https
  (testing "detects GitHub from HTTPS URL"
    (is (= :github (scm/detect-provider "https://github.com/owner/repo.git")))
    (is (= :github (scm/detect-provider "https://github.com/owner/repo")))))

(deftest detect-github-ssh
  (testing "detects GitHub from SSH URL"
    (is (= :github (scm/detect-provider "git@github.com:owner/repo.git")))))

(deftest detect-gitlab-https
  (testing "detects GitLab from HTTPS URL"
    (is (= :gitlab (scm/detect-provider "https://gitlab.com/group/project.git")))))

(deftest detect-gitlab-ssh
  (testing "detects GitLab from SSH URL"
    (is (= :gitlab (scm/detect-provider "git@gitlab.com:group/project.git")))))

;; ---------------------------------------------------------------------------
;; Provider detection — new providers
;; ---------------------------------------------------------------------------

(deftest detect-bitbucket-https
  (testing "detects Bitbucket from HTTPS URL"
    (is (= :bitbucket (scm/detect-provider "https://bitbucket.org/workspace/repo.git")))
    (is (= :bitbucket (scm/detect-provider "https://bitbucket.org/workspace/repo")))))

(deftest detect-bitbucket-ssh
  (testing "detects Bitbucket from SSH URL"
    (is (= :bitbucket (scm/detect-provider "git@bitbucket.org:workspace/repo.git")))))

(deftest detect-gitea-with-config
  (testing "detects Gitea when base-url matches repo host"
    (let [config {:scm {:gitea {:base-url "https://gitea.example.com"}}}]
      (is (= :gitea (scm/detect-provider "https://gitea.example.com/org/repo.git" config)))
      (is (= :gitea (scm/detect-provider "https://gitea.example.com/user/project" config))))))

(deftest detect-gitea-ssh-with-config
  (testing "detects Gitea from SSH URL via fallback"
    (let [config {:scm {:gitea {:base-url "https://gitea.mycompany.io"}}}]
      (is (= :gitea (scm/detect-provider "git@gitea.mycompany.io:team/repo.git" config))))))

(deftest detect-gitea-without-config
  (testing "returns nil for Gitea-like URL without config"
    (is (nil? (scm/detect-provider "https://gitea.example.com/org/repo.git")))
    (is (nil? (scm/detect-provider "https://gitea.example.com/org/repo.git" nil)))
    (is (nil? (scm/detect-provider "https://gitea.example.com/org/repo.git" {})))))

;; ---------------------------------------------------------------------------
;; Edge cases
;; ---------------------------------------------------------------------------

(deftest detect-nil-url
  (testing "returns nil for nil/empty URL"
    (is (nil? (scm/detect-provider nil)))
    (is (nil? (scm/detect-provider "")))
    (is (nil? (scm/detect-provider nil {})))))

(deftest detect-unknown-url
  (testing "returns nil for unknown host"
    (is (nil? (scm/detect-provider "https://unknown.example.com/org/repo.git")))
    (is (nil? (scm/detect-provider "https://unknown.example.com/org/repo.git" {})))))

(deftest detect-no-false-positives
  (testing "evil-github.com is not detected as GitHub"
    (is (nil? (scm/detect-provider "https://evil-github.com/o/r")))))

;; ---------------------------------------------------------------------------
;; Status mapping
;; ---------------------------------------------------------------------------

(deftest build-status-mapping
  (testing "maps internal statuses correctly"
    (is (= "success" (scm/build-status->scm-status :success)))
    (is (= "failure" (scm/build-status->scm-status :failure)))
    (is (= "error" (scm/build-status->scm-status :aborted)))
    (is (= "pending" (scm/build-status->scm-status :running)))
    (is (= "pending" (scm/build-status->scm-status :queued)))
    (is (= "pending" (scm/build-status->scm-status :waiting-approval)))))
