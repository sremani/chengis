(ns chengis.plugin.builtin.gitea-status-test
  "Tests for Gitea Status Reporter plugin.
   Covers: URL parsing, status mapping, plugin registration, HTTP mocking."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.plugin.builtin.gitea-status :as gitea]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Registry cleanup fixture
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (registry/reset-registry!)
    (f)
    (registry/reset-registry!)))

;; ---------------------------------------------------------------------------
;; Plugin Registration
;; ---------------------------------------------------------------------------

(deftest gitea-plugin-init-test
  (testing "gitea-status init! registers plugin descriptor"
    (gitea/init!)
    (let [plugin (registry/get-plugin "gitea-status")]
      (is (some? plugin))
      (is (= "gitea-status" (:name plugin)))
      (is (= "0.1.0" (:version plugin)))
      (is (contains? (:provides plugin) :status-reporter)))))

(deftest gitea-status-reporter-registered
  (testing "init! registers a Gitea status reporter"
    (gitea/init!)
    (let [reporter (registry/get-status-reporter :gitea)]
      (is (some? reporter))
      (is (satisfies? proto/ScmStatusReporter reporter)))))

;; ---------------------------------------------------------------------------
;; URL Parsing (via extract-owner-repo)
;; ---------------------------------------------------------------------------

(deftest extract-owner-repo-https
  (testing "extracts owner/repo from HTTPS URL"
    (let [reporter (gitea/->GiteaStatusReporter)
          config {:token "test-token"
                  :base-url "https://gitea.example.com"}
          build-info {:repo-url "https://gitea.example.com/myorg/myrepo.git"
                      :commit-sha "abc12345def"
                      :status "success"
                      :build-url "http://localhost:8080/builds/1"}]
      ;; Mock http/post to capture the URL
      (let [captured-url (atom nil)]
        (with-redefs [org.httpkit.client/post
                      (fn [url _opts]
                        (reset! captured-url url)
                        (deliver (promise) {:status 201 :body "{}"}))]
          (proto/report-status reporter build-info config)
          (is (= "https://gitea.example.com/api/v1/repos/myorg/myrepo/statuses/abc12345def"
                 @captured-url)))))))

(deftest extract-owner-repo-ssh
  (testing "extracts owner/repo from SSH URL"
    (let [reporter (gitea/->GiteaStatusReporter)
          config {:token "test-token"
                  :base-url "https://gitea.example.com"}
          build-info {:repo-url "git@gitea.example.com:myorg/myrepo.git"
                      :commit-sha "abc12345"
                      :status "success"
                      :build-url "http://localhost:8080/builds/1"}]
      (let [captured-url (atom nil)]
        (with-redefs [org.httpkit.client/post
                      (fn [url _opts]
                        (reset! captured-url url)
                        (deliver (promise) {:status 201 :body "{}"}))]
          (proto/report-status reporter build-info config)
          (is (= "https://gitea.example.com/api/v1/repos/myorg/myrepo/statuses/abc12345"
                 @captured-url)))))))

;; ---------------------------------------------------------------------------
;; Status Mapping
;; ---------------------------------------------------------------------------

(deftest gitea-status-mapping
  (testing "maps internal statuses to Gitea API values"
    (let [reporter (gitea/->GiteaStatusReporter)
          config {:token "test-token"
                  :base-url "https://gitea.example.com"}
          captured-body (atom nil)]
      (with-redefs [org.httpkit.client/post
                    (fn [_url opts]
                      (reset! captured-body (clojure.data.json/read-str (:body opts) :key-fn keyword))
                      (deliver (promise) {:status 201 :body "{}"}))]
        ;; Test success
        (proto/report-status reporter
          {:repo-url "https://gitea.example.com/o/r" :commit-sha "abc123"
           :status "success" :build-url "http://test/1"} config)
        (is (= "success" (:state @captured-body)))

        ;; Test failure
        (proto/report-status reporter
          {:repo-url "https://gitea.example.com/o/r" :commit-sha "abc123"
           :status "failure" :build-url "http://test/1"} config)
        (is (= "failure" (:state @captured-body)))

        ;; Test pending
        (proto/report-status reporter
          {:repo-url "https://gitea.example.com/o/r" :commit-sha "abc123"
           :status "pending" :build-url "http://test/1"} config)
        (is (= "pending" (:state @captured-body)))

        ;; Test error
        (proto/report-status reporter
          {:repo-url "https://gitea.example.com/o/r" :commit-sha "abc123"
           :status "error" :build-url "http://test/1"} config)
        (is (= "error" (:state @captured-body)))))))

;; ---------------------------------------------------------------------------
;; Error handling
;; ---------------------------------------------------------------------------

(deftest gitea-no-token-skips
  (testing "skips when no token is configured"
    (let [reporter (gitea/->GiteaStatusReporter)
          result (proto/report-status reporter
                   {:repo-url "https://gitea.example.com/o/r" :commit-sha "abc"
                    :status "success"} {:base-url "https://gitea.example.com"})]
      (is (= :skipped (:status result))))))

(deftest gitea-no-base-url-skips
  (testing "skips when no base URL is configured"
    (let [reporter (gitea/->GiteaStatusReporter)
          result (proto/report-status reporter
                   {:repo-url "https://gitea.example.com/o/r" :commit-sha "abc"
                    :status "success"} {:token "test-token"})]
      (is (= :skipped (:status result))))))

(deftest gitea-api-failure
  (testing "handles API failure responses"
    (let [reporter (gitea/->GiteaStatusReporter)
          config {:token "test-token" :base-url "https://gitea.example.com"}]
      (with-redefs [org.httpkit.client/post
                    (fn [_url _opts]
                      (deliver (promise) {:status 403 :body "Forbidden"}))]
        (let [result (proto/report-status reporter
                       {:repo-url "https://gitea.example.com/o/r" :commit-sha "abc123"
                        :status "success" :build-url "http://test/1"} config)]
          (is (= :failed (:status result)))
          (is (re-find #"HTTP 403" (:details result))))))))

(deftest gitea-invalid-repo-url
  (testing "fails gracefully with unparseable URL"
    (let [reporter (gitea/->GiteaStatusReporter)
          config {:token "test-token" :base-url "https://gitea.example.com"}
          result (proto/report-status reporter
                   {:repo-url "not-a-url" :commit-sha "abc"
                    :status "success"} config)]
      (is (= :failed (:status result)))
      (is (re-find #"parse" (:details result))))))

(deftest gitea-auth-header
  (testing "sends token in Authorization header"
    (let [reporter (gitea/->GiteaStatusReporter)
          config {:token "my-gitea-token" :base-url "https://gitea.example.com"}
          captured-headers (atom nil)]
      (with-redefs [org.httpkit.client/post
                    (fn [_url opts]
                      (reset! captured-headers (:headers opts))
                      (deliver (promise) {:status 201 :body "{}"}))]
        (proto/report-status reporter
          {:repo-url "https://gitea.example.com/o/r" :commit-sha "abc123"
           :status "success" :build-url "http://test/1"} config)
        (is (= "token my-gitea-token" (get @captured-headers "Authorization")))))))
