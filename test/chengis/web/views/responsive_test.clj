(ns chengis.web.views.responsive-test
  (:require [clojure.test :refer :all]
            [chengis.web.views.layout :as layout]
            [chengis.web.views.components :as c]
            [chengis.test-helpers :refer [hiccup-contains? hiccup-find-class]]
            [clojure.string :as str]))

(def ^:private sample-layout
  (layout/base-layout {:title "Test" :csrf-token "tok" :user nil :auth-enabled false}
                      [:p "Hello"]))

;; --- Layout Responsive Tests ---

(deftest layout-has-viewport-meta-tag
  (testing "Layout includes viewport meta tag for responsive design"
    (is (str/includes? sample-layout "width=device-width, initial-scale=1.0"))))

(deftest layout-has-hamburger-menu
  (testing "Layout includes hamburger checkbox toggle for mobile nav"
    (is (str/includes? sample-layout "nav-toggle"))
    (is (str/includes? sample-layout "\u2630"))))

(deftest layout-nav-has-responsive-classes
  (testing "Nav links container has responsive hidden/flex classes"
    (is (str/includes? sample-layout "hidden peer-checked:flex md:flex"))
    (is (str/includes? sample-layout "flex-col md:flex-row"))))

(deftest layout-nav-links-responsive
  (testing "Nav links have responsive width classes"
    (is (str/includes? sample-layout "w-full md:w-auto"))))

(deftest layout-hamburger-hidden-on-desktop
  (testing "Hamburger label has md:hidden class"
    (is (str/includes? sample-layout "md:hidden"))))

;; --- Dark Mode Tests ---

(deftest layout-has-dark-mode-body-classes
  (testing "Body has dark mode background and text classes"
    (is (str/includes? sample-layout "dark:bg-gray-900"))
    (is (str/includes? sample-layout "dark:text-gray-100"))))

(deftest layout-has-tailwind-dark-mode-config
  (testing "Layout includes Tailwind dark mode class config"
    (is (str/includes? sample-layout "darkMode: 'class'"))))

(deftest layout-has-theme-init-script
  (testing "Layout includes theme initialization script"
    (is (str/includes? sample-layout "localStorage.getItem('theme')"))
    (is (str/includes? sample-layout "prefers-color-scheme: dark"))))

(deftest layout-has-theme-toggle-button
  (testing "Layout includes theme toggle button"
    (is (str/includes? sample-layout "theme-toggle"))
    (is (str/includes? sample-layout "Toggle dark/light theme"))))

(deftest layout-footer-has-dark-mode
  (testing "Footer has dark mode border and text classes"
    (is (str/includes? sample-layout "dark:border-gray-700"))
    (is (str/includes? sample-layout "dark:text-gray-500"))))

(deftest layout-nav-has-dark-mode
  (testing "Nav has dark mode background"
    (is (str/includes? sample-layout "dark:bg-gray-950"))))

;; --- Component Dark Mode Tests ---

(deftest status-badge-has-dark-mode
  (testing "Status badges include dark mode variants"
    (let [badge (c/status-badge :success)]
      (is (hiccup-find-class badge "dark:bg-green-900")))
    (let [badge (c/status-badge :failure)]
      (is (hiccup-find-class badge "dark:bg-red-900")))))

(deftest stat-card-has-dark-mode
  (testing "Stat card includes dark mode classes"
    (let [card (c/stat-card 42 "Total")]
      (is (hiccup-find-class card "dark:bg-gray-800"))
      (is (hiccup-find-class card "dark:border-gray-700")))))

(deftest build-table-has-dark-mode
  (testing "Build table includes dark mode classes"
    (let [builds [{:id "1" :build-number 1 :status :success :started-at "now"}]
          table (c/build-table builds)]
      (is (hiccup-find-class table "dark:bg-gray-800"))
      (is (hiccup-find-class table "dark:divide-gray-700")))))

(deftest card-component-has-dark-mode
  (testing "Card component includes dark mode classes"
    (let [card (c/card {:title "Test"} [:p "content"])]
      (is (hiccup-find-class card "dark:bg-gray-800"))
      (is (hiccup-find-class card "dark:border-gray-700")))))

(deftest page-header-has-dark-mode
  (testing "Page header includes dark mode text color"
    (let [header (c/page-header "Test Title")]
      (is (hiccup-find-class header "dark:text-gray-100")))))

(deftest pipeline-graph-has-dark-mode
  (testing "Pipeline graph includes dark mode classes"
    (let [stages [{:stage-name "Build" :steps [{:step-name "Compile" :type :shell}]}]
          graph (c/pipeline-graph stages)]
      (is (hiccup-find-class graph "dark:bg-gray-800"))
      (is (hiccup-find-class graph "dark:border-gray-700")))))

(deftest build-history-chart-has-dark-mode
  (testing "Build history chart includes dark mode classes"
    (let [builds [{:id "1" :build-number 1 :status :success}]
          chart (c/build-history-chart builds)]
      (is (hiccup-find-class chart "dark:bg-gray-800"))
      (is (hiccup-find-class chart "dark:text-gray-400")))))

(deftest build-stats-row-has-dark-mode
  (testing "Build stats row includes dark mode classes"
    (let [stats {:total 10 :success 8 :failure 1 :aborted 1 :success-rate 0.8}
          row (c/build-stats-row stats)]
      (is (hiccup-find-class row "dark:bg-gray-800"))
      (is (hiccup-find-class row "dark:text-gray-400")))))

;; --- Login link responsive test ---

(deftest layout-login-responsive-when-auth-enabled
  (testing "Login link has responsive classes when auth is enabled"
    (let [layout-html (layout/base-layout {:title "T" :csrf-token "t" :user nil :auth-enabled true}
                                          [:p "x"])]
      (is (str/includes? layout-html "Login"))
      (is (str/includes? layout-html "md:border-l")))))

;; --- Search nav link ---

(deftest layout-has-search-nav-link
  (testing "Layout includes Search nav link"
    (is (str/includes? sample-layout "Search"))
    (is (str/includes? sample-layout "/search/logs"))))
