(ns chengis.web.shared-resources-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.web.views.shared-resources :as shared-views]
            [chengis.test-helpers :as h]))

;; ---------------------------------------------------------------------------
;; render-shared-resources-page
;; ---------------------------------------------------------------------------

(deftest render-shared-resources-page-returns-html-string
  (testing "render-shared-resources-page returns an HTML string"
    (let [result (shared-views/render-shared-resources-page
                   {:grants-from []
                    :grants-to []
                    :organizations []
                    :csrf-token "test-token"
                    :user {:username "admin" :role "admin"}
                    :auth-enabled true})]
      (is (string? result))
      (is (.contains ^String result "<!DOCTYPE html"))
      (is (.contains ^String result "Shared Resources")))))

(deftest render-shared-resources-page-empty-state
  (testing "render-shared-resources-page shows empty state when no grants"
    (let [result (shared-views/render-shared-resources-page
                   {:grants-from []
                    :grants-to []
                    :organizations []
                    :csrf-token "test-token"
                    :user {:username "admin" :role "admin"}
                    :auth-enabled true})]
      (is (.contains ^String result "No resources shared by this organization yet."))
      (is (.contains ^String result "No resources shared with this organization yet.")))))

(deftest render-shared-resources-page-with-grants
  (testing "render-shared-resources-page shows grants table when grants exist"
    (let [result (shared-views/render-shared-resources-page
                   {:grants-from [{:id "g1"
                                   :source-org-id "org-a"
                                   :target-org-id "org-b"
                                   :resource-type "agent-label"
                                   :resource-id "docker"
                                   :granted-by "admin-user"
                                   :expires-at nil}]
                    :grants-to [{:id "g2"
                                 :source-org-id "org-c"
                                 :target-org-id "org-a"
                                 :resource-type "template"
                                 :resource-id "tmpl-1"
                                 :granted-by "other-admin"
                                 :expires-at "2099-12-31"}]
                    :organizations [{:id "org-b" :name "Beta Corp" :slug "beta"}
                                    {:id "org-c" :name "Gamma Inc" :slug "gamma"}]
                    :csrf-token "test-token"
                    :user {:username "admin" :role "admin"}
                    :auth-enabled true})]
      ;; Grants-from table content
      (is (.contains ^String result "docker"))
      (is (.contains ^String result "agent-label"))
      (is (.contains ^String result "org-b"))
      (is (.contains ^String result "Revoke"))
      ;; Grants-to table content
      (is (.contains ^String result "tmpl-1"))
      (is (.contains ^String result "template"))
      (is (.contains ^String result "org-c")))))

(deftest render-shared-resources-page-includes-csrf-token
  (testing "render-shared-resources-page includes CSRF token in form"
    (let [result (shared-views/render-shared-resources-page
                   {:grants-from []
                    :grants-to []
                    :organizations [{:id "org-b" :name "Beta" :slug "beta"}]
                    :csrf-token "my-csrf-secret-123"
                    :user {:username "admin" :role "admin"}
                    :auth-enabled true})]
      ;; CSRF token appears in hidden form fields
      (is (.contains ^String result "my-csrf-secret-123"))
      ;; The create grant form is present
      (is (.contains ^String result "Create Grant"))
      (is (.contains ^String result "Grant Access"))
      ;; Organization dropdown
      (is (.contains ^String result "Beta (beta)")))))
