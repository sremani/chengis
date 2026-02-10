(ns chengis.web.notifications-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.web.views.notifications :as notifications]
            [chengis.web.views.layout :as layout]))

(deftest notification-toggle-test
  (testing "notification-toggle produces Hiccup structure"
    (let [toggle (notifications/notification-toggle)]
      (is (vector? toggle))
      (is (= :button (first toggle))))))

(deftest notification-script-test
  (testing "notification-script produces a script tag"
    (let [script (notifications/notification-script)]
      (is (vector? script))
      (is (= :script (first script))))))

(deftest layout-includes-notifications-when-enabled-test
  (testing "base-layout includes notification script when enabled"
    (let [html (layout/base-layout
                 {:title "Test" :notifications-enabled true}
                 [:div "Content"])]
      ;; Should contain the notification toggle
      (is (re-find #"notification-toggle" html))
      ;; Should contain the notification script
      (is (re-find #"EventSource" html)))))

(deftest layout-excludes-notifications-when-disabled-test
  (testing "base-layout excludes notification elements when disabled"
    (let [html (layout/base-layout
                 {:title "Test" :notifications-enabled false}
                 [:div "Content"])]
      ;; Should NOT contain the notification toggle
      (is (not (re-find #"notification-toggle" html)))
      ;; Should NOT contain the EventSource script
      (is (not (re-find #"EventSource" html))))))

(deftest layout-default-no-notifications-test
  (testing "base-layout has no notifications by default"
    (let [html (layout/base-layout
                 {:title "Test"}
                 [:div "Content"])]
      (is (not (re-find #"notification-toggle" html))))))
