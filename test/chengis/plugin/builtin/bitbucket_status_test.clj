(ns chengis.plugin.builtin.bitbucket-status-test
  "Tests for Bitbucket Status Reporter plugin.
   Covers: URL parsing, status mapping, plugin registration, Basic Auth, HTTP mocking."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.plugin.builtin.bitbucket-status :as bitbucket]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry])
  (:import [java.util Base64]))

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

(deftest bitbucket-plugin-init-test
  (testing "bitbucket-status init! registers plugin descriptor"
    (bitbucket/init!)
    (let [plugin (registry/get-plugin "bitbucket-status")]
      (is (some? plugin))
      (is (= "bitbucket-status" (:name plugin)))
      (is (= "0.1.0" (:version plugin)))
      (is (contains? (:provides plugin) :status-reporter)))))

(deftest bitbucket-status-reporter-registered
  (testing "init! registers a Bitbucket status reporter"
    (bitbucket/init!)
    (let [reporter (registry/get-status-reporter :bitbucket)]
      (is (some? reporter))
      (is (satisfies? proto/ScmStatusReporter reporter)))))

;; ---------------------------------------------------------------------------
;; URL Parsing (workspace/repo extraction)
;; ---------------------------------------------------------------------------

(deftest extract-workspace-repo-https
  (testing "extracts workspace/repo from HTTPS URL"
    (let [reporter (bitbucket/->BitbucketStatusReporter)
          config {:username "testuser" :app-password "testpass"
                  :base-url "https://api.bitbucket.org/2.0"}
          captured-url (atom nil)]
      (with-redefs [org.httpkit.client/post
                    (fn [url _opts]
                      (reset! captured-url url)
                      (deliver (promise) {:status 201 :body "{}"}))]
        (proto/report-status reporter
          {:repo-url "https://bitbucket.org/myworkspace/myrepo.git"
           :commit-sha "abc12345def"
           :status "success" :build-url "http://test/1"} config)
        (is (= "https://api.bitbucket.org/2.0/repositories/myworkspace/myrepo/commit/abc12345def/statuses/build"
               @captured-url))))))

(deftest extract-workspace-repo-ssh
  (testing "extracts workspace/repo from SSH URL"
    (let [reporter (bitbucket/->BitbucketStatusReporter)
          config {:username "testuser" :app-password "testpass"}
          captured-url (atom nil)]
      (with-redefs [org.httpkit.client/post
                    (fn [url _opts]
                      (reset! captured-url url)
                      (deliver (promise) {:status 201 :body "{}"}))]
        (proto/report-status reporter
          {:repo-url "git@bitbucket.org:myworkspace/myrepo.git"
           :commit-sha "abc123"
           :status "success" :build-url "http://test/1"} config)
        (is (re-find #"repositories/myworkspace/myrepo" @captured-url))))))

;; ---------------------------------------------------------------------------
;; Status Mapping
;; ---------------------------------------------------------------------------

(deftest bitbucket-status-mapping
  (testing "maps internal statuses to Bitbucket API values"
    (let [reporter (bitbucket/->BitbucketStatusReporter)
          config {:username "user" :app-password "pass"}
          captured-body (atom nil)]
      (with-redefs [org.httpkit.client/post
                    (fn [_url opts]
                      (reset! captured-body (clojure.data.json/read-str (:body opts) :key-fn keyword))
                      (deliver (promise) {:status 201 :body "{}"}))]
        ;; Test success → SUCCESSFUL
        (proto/report-status reporter
          {:repo-url "https://bitbucket.org/w/r" :commit-sha "abc123"
           :status "success" :build-url "http://test/1"} config)
        (is (= "SUCCESSFUL" (:state @captured-body)))

        ;; Test failure → FAILED
        (proto/report-status reporter
          {:repo-url "https://bitbucket.org/w/r" :commit-sha "abc123"
           :status "failure" :build-url "http://test/1"} config)
        (is (= "FAILED" (:state @captured-body)))

        ;; Test pending → INPROGRESS
        (proto/report-status reporter
          {:repo-url "https://bitbucket.org/w/r" :commit-sha "abc123"
           :status "pending" :build-url "http://test/1"} config)
        (is (= "INPROGRESS" (:state @captured-body)))

        ;; Test error → FAILED
        (proto/report-status reporter
          {:repo-url "https://bitbucket.org/w/r" :commit-sha "abc123"
           :status "error" :build-url "http://test/1"} config)
        (is (= "FAILED" (:state @captured-body)))))))

;; ---------------------------------------------------------------------------
;; Authentication
;; ---------------------------------------------------------------------------

(deftest bitbucket-basic-auth-header
  (testing "sends correct Basic Auth header"
    (let [reporter (bitbucket/->BitbucketStatusReporter)
          config {:username "myuser" :app-password "myapppass"}
          captured-headers (atom nil)]
      (with-redefs [org.httpkit.client/post
                    (fn [_url opts]
                      (reset! captured-headers (:headers opts))
                      (deliver (promise) {:status 201 :body "{}"}))]
        (proto/report-status reporter
          {:repo-url "https://bitbucket.org/w/r" :commit-sha "abc123"
           :status "success" :build-url "http://test/1"} config)
        (let [auth-header (get @captured-headers "Authorization")
              expected-encoded (.encodeToString (Base64/getEncoder) (.getBytes "myuser:myapppass" "UTF-8"))]
          (is (= (str "Basic " expected-encoded) auth-header)))))))

;; ---------------------------------------------------------------------------
;; Error handling
;; ---------------------------------------------------------------------------

(deftest bitbucket-no-credentials-skips
  (testing "skips when no username configured"
    (let [reporter (bitbucket/->BitbucketStatusReporter)
          result (proto/report-status reporter
                   {:repo-url "https://bitbucket.org/w/r" :commit-sha "abc"
                    :status "success"} {:app-password "pass"})]
      (is (= :skipped (:status result)))))

  (testing "skips when no app-password configured"
    (let [reporter (bitbucket/->BitbucketStatusReporter)
          result (proto/report-status reporter
                   {:repo-url "https://bitbucket.org/w/r" :commit-sha "abc"
                    :status "success"} {:username "user"})]
      (is (= :skipped (:status result))))))

(deftest bitbucket-api-failure
  (testing "handles API failure responses"
    (let [reporter (bitbucket/->BitbucketStatusReporter)
          config {:username "user" :app-password "pass"}]
      (with-redefs [org.httpkit.client/post
                    (fn [_url _opts]
                      (deliver (promise) {:status 401 :body "Unauthorized"}))]
        (let [result (proto/report-status reporter
                       {:repo-url "https://bitbucket.org/w/r" :commit-sha "abc123"
                        :status "success" :build-url "http://test/1"} config)]
          (is (= :failed (:status result)))
          (is (re-find #"HTTP 401" (:details result))))))))

(deftest bitbucket-invalid-repo-url
  (testing "fails gracefully with non-bitbucket URL"
    (let [reporter (bitbucket/->BitbucketStatusReporter)
          config {:username "user" :app-password "pass"}
          result (proto/report-status reporter
                   {:repo-url "https://github.com/o/r" :commit-sha "abc"
                    :status "success"} config)]
      (is (= :failed (:status result)))
      (is (re-find #"parse" (:details result))))))

(deftest bitbucket-body-fields
  (testing "sends correct body fields including key and name"
    (let [reporter (bitbucket/->BitbucketStatusReporter)
          config {:username "user" :app-password "pass"}
          captured-body (atom nil)]
      (with-redefs [org.httpkit.client/post
                    (fn [_url opts]
                      (reset! captured-body (clojure.data.json/read-str (:body opts) :key-fn keyword))
                      (deliver (promise) {:status 201 :body "{}"}))]
        (proto/report-status reporter
          {:repo-url "https://bitbucket.org/w/r" :commit-sha "abc123"
           :status "success" :build-url "http://test/1"
           :context "chengis/lint" :description "Lint check"} config)
        (is (= "chengis/lint" (:key @captured-body)))
        (is (= "chengis/lint" (:name @captured-body)))
        (is (= "Lint check" (:description @captured-body)))
        (is (= "http://test/1" (:url @captured-body)))))))
