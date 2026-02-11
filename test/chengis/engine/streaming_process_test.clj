(ns chengis.engine.streaming-process-test
  (:require [clojure.test :refer :all]
            [chengis.engine.streaming-process :as sp]))

(deftest basic-command-execution-test
  (testing "execute simple echo command"
    (let [result (sp/execute-command-streaming
                   {:command "echo 'hello world'"
                    :chunk-size 100})]
      (is (= 0 (:exit-code result)))
      (is (false? (:timed-out? result)))
      (is (= 1 (:stdout-lines result)))
      (is (pos? (:duration-ms result))))))

(deftest multi-line-output-test
  (testing "capture multiple lines"
    (let [lines (atom [])
          result (sp/execute-command-streaming
                   {:command "printf 'line1\\nline2\\nline3\\n'"
                    :chunk-size 100
                    :on-line (fn [source line-num text]
                               (swap! lines conj {:source source :line line-num :text text}))})]
      (is (= 0 (:exit-code result)))
      (is (= 3 (:stdout-lines result)))
      (is (= 3 (count @lines)))
      (is (= "line1" (:text (first @lines))))
      (is (= "line3" (:text (last @lines)))))))

(deftest chunking-test
  (testing "output is split into chunks"
    (let [chunks (atom [])
          result (sp/execute-command-streaming
                   {:command "seq 1 25"
                    :chunk-size 10
                    :on-chunk (fn [chunk] (swap! chunks conj chunk))})]
      (is (= 0 (:exit-code result)))
      (is (= 25 (:stdout-lines result)))
      ;; 25 lines / 10 per chunk = 3 chunks (10, 10, 5)
      (is (= 3 (count @chunks)))
      (is (= 10 (:line-count (first @chunks))))
      (is (= 10 (:line-count (second @chunks))))
      (is (= 5 (:line-count (nth @chunks 2))))
      ;; Verify line-start progression
      (is (= 0 (:line-start (first @chunks))))
      (is (= 10 (:line-start (second @chunks))))
      (is (= 20 (:line-start (nth @chunks 2)))))))

(deftest stderr-capture-test
  (testing "stderr is captured separately"
    (let [chunks (atom [])
          result (sp/execute-command-streaming
                   {:command "echo 'stdout' && echo 'stderr' >&2"
                    :chunk-size 100
                    :on-chunk (fn [chunk] (swap! chunks conj chunk))})]
      (is (= 0 (:exit-code result)))
      (let [stdout-chunks (filter #(= "stdout" (:source %)) @chunks)
            stderr-chunks (filter #(= "stderr" (:source %)) @chunks)]
        (is (= 1 (count stdout-chunks)))
        (is (= 1 (count stderr-chunks)))))))

(deftest timeout-test
  (testing "command times out"
    (let [result (sp/execute-command-streaming
                   {:command "sleep 10"
                    :timeout 100})]
      (is (true? (:timed-out? result)))
      (is (= -1 (:exit-code result))))))

(deftest nonzero-exit-code-test
  (testing "captures non-zero exit code"
    (let [result (sp/execute-command-streaming
                   {:command "exit 42"
                    :chunk-size 100})]
      (is (= 42 (:exit-code result)))
      (is (false? (:timed-out? result))))))

(deftest mask-values-test
  (testing "sensitive values are masked in output"
    (let [lines (atom [])
          result (sp/execute-command-streaming
                   {:command "echo 'secret-password-123'"
                    :mask-values ["secret-password-123"]
                    :chunk-size 100
                    :on-line (fn [source line-num text]
                               (swap! lines conj text))})]
      (is (= 0 (:exit-code result)))
      (is (= "****" (first @lines))))))

(deftest empty-output-test
  (testing "command with no output"
    (let [result (sp/execute-command-streaming
                   {:command "true"
                    :chunk-size 100})]
      (is (= 0 (:exit-code result)))
      (is (= 0 (:stdout-lines result))))))

(deftest working-directory-test
  (testing "command runs in specified directory"
    (let [result (sp/execute-command-streaming
                   {:command "pwd"
                    :dir "/tmp"
                    :chunk-size 100})]
      (is (= 0 (:exit-code result))))))

(deftest environment-variables-test
  (testing "custom env vars are passed to command"
    (let [lines (atom [])
          result (sp/execute-command-streaming
                   {:command "echo $MY_VAR"
                    :env {"MY_VAR" "hello123"}
                    :chunk-size 100
                    :on-line (fn [_ _ text] (swap! lines conj text))})]
      (is (= 0 (:exit-code result)))
      (is (= "hello123" (first @lines))))))

(deftest large-output-chunking-test
  (testing "large output is properly chunked"
    (let [chunks (atom [])
          result (sp/execute-command-streaming
                   {:command "seq 1 5000"
                    :chunk-size 1000
                    :on-chunk (fn [chunk] (swap! chunks conj chunk))})]
      (is (= 0 (:exit-code result)))
      (is (= 5000 (:stdout-lines result)))
      (is (= 5 (count @chunks)))
      (is (every? #(= 1000 (:line-count %)) @chunks)))))
