(ns cljest.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [cljest.config :as cfg]))

;; ---------------------------------------------------------------------------
;; Default resolution
;; ---------------------------------------------------------------------------

(deftest defaults-with-empty-project-and-no-args
  (testing "resolve-config with empty project and no CLI args returns defaults"
    (let [config (cfg/resolve-config {} [])]
      (is (= :standard (:operators config)))
      (is (= 80 (:threshold config)))
      (is (= 30000 (:timeout config)))
      (is (= "target/cljest" (:output-dir config)))
      (is (= [:text] (:output-format config)))
      (is (= true (:skip-equivalent config)))
      (is (= false (:dry-run config)))
      (is (= false (:verbose config))))))

(deftest defaults-source-paths-from-project
  (testing "source-paths fall back to project :source-paths"
    (let [config (cfg/resolve-config {:source-paths ["src/main"]} [])]
      (is (= ["src/main"] (:source-paths config))))))

(deftest defaults-source-paths-fallback
  (testing "source-paths defaults to [\"src\"] when project has none"
    (let [config (cfg/resolve-config {} [])]
      (is (= ["src"] (:source-paths config))))))

(deftest defaults-test-paths-from-project
  (testing "test-paths fall back to project :test-paths"
    (let [config (cfg/resolve-config {:test-paths ["test/unit"]} [])]
      (is (= ["test/unit"] (:test-paths config))))))

(deftest defaults-test-paths-fallback
  (testing "test-paths defaults to [\"test\"] when project has none"
    (let [config (cfg/resolve-config {} [])]
      (is (= ["test"] (:test-paths config))))))

;; ---------------------------------------------------------------------------
;; Project :cljest overrides defaults
;; ---------------------------------------------------------------------------

(deftest project-overrides-threshold
  (testing "project :cljest overrides default threshold"
    (let [config (cfg/resolve-config {:cljest {:threshold 95}} [])]
      (is (= 95 (:threshold config))))))

(deftest project-overrides-operators
  (testing "project :cljest overrides default operators"
    (let [config (cfg/resolve-config {:cljest {:operators :fast}} [])]
      (is (= :fast (:operators config))))))

(deftest project-overrides-timeout
  (testing "project :cljest overrides default timeout"
    (let [config (cfg/resolve-config {:cljest {:timeout 60000}} [])]
      (is (= 60000 (:timeout config))))))

;; ---------------------------------------------------------------------------
;; CLI args override project config
;; ---------------------------------------------------------------------------

(deftest cli-overrides-project-threshold
  (testing "CLI --threshold overrides project threshold"
    (let [config (cfg/resolve-config {:cljest {:threshold 95}} ["--threshold" "90"])]
      (is (= 90 (:threshold config))))))

(deftest cli-overrides-project-operators
  (testing "CLI --operators overrides project operators"
    (let [config (cfg/resolve-config {:cljest {:operators :comprehensive}} ["--operators" "fast"])]
      (is (= :fast (:operators config))))))

;; ---------------------------------------------------------------------------
;; Individual CLI options
;; ---------------------------------------------------------------------------

(deftest cli-threshold
  (testing "--threshold 90 sets threshold to 90"
    (let [config (cfg/resolve-config {} ["--threshold" "90"])]
      (is (= 90 (:threshold config))))))

(deftest cli-operators-fast
  (testing "--operators fast sets operators to :fast"
    (let [config (cfg/resolve-config {} ["--operators" "fast"])]
      (is (= :fast (:operators config))))))

(deftest cli-dry-run
  (testing "--dry-run sets dry-run to true"
    (let [config (cfg/resolve-config {} ["--dry-run"])]
      (is (= true (:dry-run config))))))

(deftest cli-format-html
  (testing "--format html sets output-format to [:html]"
    (let [config (cfg/resolve-config {} ["--format" "html"])]
      (is (= [:html] (:output-format config))))))

(deftest cli-format-both
  (testing "--format both sets output-format to [:text :html]"
    (let [config (cfg/resolve-config {} ["--format" "both"])]
      (is (= [:text :html] (:output-format config))))))

(deftest cli-format-text
  (testing "--format text sets output-format to [:text]"
    (let [config (cfg/resolve-config {} ["--format" "text"])]
      (is (= [:text] (:output-format config))))))

(deftest cli-verbose
  (testing "--verbose sets verbose to true"
    (let [config (cfg/resolve-config {} ["--verbose"])]
      (is (= true (:verbose config))))))

(deftest cli-output-dir
  (testing "--output-dir sets output directory"
    (let [config (cfg/resolve-config {} ["--output-dir" "custom/reports"])]
      (is (= "custom/reports" (:output-dir config))))))

(deftest cli-timeout
  (testing "--timeout sets per-mutation timeout"
    (let [config (cfg/resolve-config {} ["--timeout" "5000"])]
      (is (= 5000 (:timeout config))))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(deftest invalid-threshold-too-high
  (testing "threshold > 100 raises error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cfg/resolve-config {} ["--threshold" "150"])))))

(deftest invalid-threshold-negative
  (testing "threshold < 0 raises error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cfg/resolve-config {} ["--threshold" "-1"])))))

(deftest invalid-operators-preset
  (testing "invalid operator preset raises error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cfg/resolve-config {} ["--operators" "invalid"])))))

(deftest invalid-timeout-zero
  (testing "timeout of 0 raises error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cfg/resolve-config {} ["--timeout" "0"])))))

;; ---------------------------------------------------------------------------
;; help-text
;; ---------------------------------------------------------------------------

(deftest help-text-is-non-empty
  (testing "help-text returns a non-empty string"
    (let [text (cfg/help-text)]
      (is (string? text))
      (is (pos? (count text)))
      (is (clojure.string/includes? text "cljest"))
      (is (clojure.string/includes? text "--threshold"))
      (is (clojure.string/includes? text "--operators")))))

;; ---------------------------------------------------------------------------
;; Namespace regex compilation
;; ---------------------------------------------------------------------------

(deftest namespaces-regex-compilation
  (testing "--namespaces compiles to a regex pattern"
    (let [config (cfg/resolve-config {} ["--namespaces" "engine.*"])]
      (is (vector? (:namespaces config)))
      (is (= 1 (count (:namespaces config))))
      (is (instance? java.util.regex.Pattern (first (:namespaces config))))
      (is (re-matches (first (:namespaces config)) "engine.core"))
      (is (nil? (re-matches (first (:namespaces config)) "web.handler"))))))

(deftest exclude-namespaces-regex-compilation
  (testing "--exclude-namespaces compiles to a regex pattern"
    (let [config (cfg/resolve-config {} ["--exclude-namespaces" "test\\..*"])]
      (is (vector? (:exclude-namespaces config)))
      (is (instance? java.util.regex.Pattern (first (:exclude-namespaces config))))
      (is (re-matches (first (:exclude-namespaces config)) "test.core")))))

;; ---------------------------------------------------------------------------
;; Merge precedence: defaults < project < CLI
;; ---------------------------------------------------------------------------

(deftest full-merge-precedence
  (testing "CLI > project > defaults for all layers"
    (let [config (cfg/resolve-config
                   {:cljest {:threshold 70
                             :operators :fast
                             :timeout 10000}}
                   ["--threshold" "50"
                    "--operators" "comprehensive"])]
      ;; CLI wins for threshold and operators
      (is (= 50 (:threshold config)))
      (is (= :comprehensive (:operators config)))
      ;; Project wins for timeout (no CLI override)
      (is (= 10000 (:timeout config)))
      ;; Defaults win for the rest
      (is (= [:text] (:output-format config)))
      (is (= false (:dry-run config))))))
