(ns cljest.runner
  "Test execution engine — builds a quoted Clojure form that runs inside
   the project's JVM via leiningen.core.eval/eval-in-project.

   For each source namespace, one JVM is launched. Inside that JVM, the
   mutation loop runs: for each mutation, the source file is overwritten,
   the namespace is reloaded, tests run with a timeout, and the result
   (killed/survived/timed-out/error) is recorded. The original source is
   always restored via a finally block."
  (:require [cljest.mutator :as mutator]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [leiningen.core.eval :as eval]
            [leiningen.core.main :as main]))

;; ---------------------------------------------------------------------------
;; Form construction
;; ---------------------------------------------------------------------------

(defn build-mutation-form
  "Construct a quoted Clojure form that, when eval'd in the project JVM,
   runs all mutations for a single source namespace and writes results
   to a temp EDN file.

   Arguments:
     source-ns      — symbol, the namespace being mutated
     source-file    — string, absolute path to the source file
     test-nses      — vec of symbols, test namespaces to run
     mutations      — vec of {:position [r c] :operator-id :id :mutated-source \"...\"}
     timeout-ms     — long, per-mutation test timeout
     results-file   — string, path to write EDN results"
  [source-ns source-file test-nses mutations timeout-ms results-file]
  (let [test-ns-forms (mapv (fn [ns] `(quote ~ns)) test-nses)
        mutation-data (mapv (fn [m]
                              {:position (:position m)
                               :operator-id (:operator-id m)
                               :mutated-source (:mutated-source m)
                               :original-form (pr-str (:original-form m))})
                            mutations)]
    `(do
       (require 'clojure.test)
       ;; Require all test namespaces upfront
       ~@(for [tns test-nses]
           `(require (quote ~tns)))

       (let [original# (slurp ~source-file)
             results# (atom [])
             mutation-vec# ~(vec mutation-data)]
         (try
           (doseq [mutation# mutation-vec#]
             (let [mutated-src# (:mutated-source mutation#)
                   op-id# (:operator-id mutation#)
                   pos# (:position mutation#)]
               ;; Write mutated source
               (spit ~source-file mutated-src#)
               (try
                 ;; Reload the namespace to pick up the mutation
                 (require (quote ~source-ns) :reload)
                 ;; Run tests with timeout
                 (let [f# (future
                            (binding [clojure.test/*test-out* (java.io.StringWriter.)]
                              (let [result# (apply clojure.test/run-tests
                                                   (list ~@test-ns-forms))]
                                {:test (:test result# 0)
                                 :pass (:pass result# 0)
                                 :fail (:fail result# 0)
                                 :error (:error result# 0)})))
                       test-result# (deref f# ~timeout-ms ::timeout)]
                   (when (= test-result# ::timeout)
                     (future-cancel f#))
                   (swap! results# conj
                          {:position pos#
                           :operator-id op-id#
                           :original-form (:original-form mutation#)
                           :status (cond
                                     (= test-result# ::timeout) :timed-out
                                     (pos? (+ (get test-result# :fail 0)
                                              (get test-result# :error 0)))
                                     :killed
                                     :else :survived)
                           :test-result (when (map? test-result#) test-result#)}))
                 (catch Throwable e#
                   ;; Compilation error or crash → mutation killed
                   (swap! results# conj
                          {:position pos#
                           :operator-id op-id#
                           :original-form (:original-form mutation#)
                           :status :killed
                           :error (.getMessage e#)})))))
           (finally
             ;; ALWAYS restore original source
             (spit ~source-file original#)
             (try (require (quote ~source-ns) :reload) (catch Throwable _#))))
         ;; Write results to temp file
         (spit ~results-file (pr-str @results#))))))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defn- make-results-file
  "Generate a unique temp file path for results."
  []
  (str (System/getProperty "java.io.tmpdir")
       "/cljest-results-" (System/nanoTime) ".edn"))

(defn run-mutations-for-namespace
  "Run all mutations for a source namespace.

   1. Pre-compute mutated source strings (in Lein JVM via mutator)
   2. Build the mutation form
   3. Launch project JVM via eval-in-project
   4. Read results from temp EDN file

   Arguments:
     project    — Leiningen project map
     src-ns     — namespace symbol
     src-file   — absolute source file path
     test-nses  — vec of test namespace symbols
     mutations  — seq of expanded mutation maps from mutator/expand-mutations
     config     — resolved config map

   Returns a vec of result maps with :status :killed/:survived/:timed-out/:error."
  [project src-ns src-file test-nses mutations config]
  (let [results-file (make-results-file)
        timeout-ms (:timeout config 30000)
        verbose? (:verbose config)
        ;; Pre-compute all mutated source strings
        mutations-with-source
        (mapv (fn [m]
                (try
                  (let [mutated (mutator/apply-mutation src-file
                                                        (:position m)
                                                        (:operator-id m))]
                    (if mutated
                      (assoc m :mutated-source mutated)
                      nil))
                  (catch Exception e
                    (when verbose?
                      (main/info "  Skipping mutation at" (:position m)
                                 "- transform error:" (.getMessage e)))
                    nil)))
              mutations)
        valid-mutations (filterv some? mutations-with-source)]

    (if (empty? valid-mutations)
      (do
        (when verbose?
          (main/info "  No valid mutations for" (str src-ns)))
        [])

      ;; Build the form
      (let [form (build-mutation-form src-ns src-file test-nses
                                      valid-mutations timeout-ms results-file)]
        ;; Run in project JVM
        (try
          (eval/eval-in-project project form
            `(do (require 'clojure.test)
                 ~@(for [tns test-nses]
                     `(require (quote ~tns)))))
          ;; Read results
          (let [results-raw (slurp results-file)
                results (edn/read-string results-raw)]
            (io/delete-file results-file true)
            (vec results))
          (catch Exception e
            (main/warn "Error running mutations for" (str src-ns) "-" (.getMessage e))
            (io/delete-file results-file true)
            ;; Return all mutations as errors
            (mapv #(assoc % :status :error :error (.getMessage e))
                  valid-mutations)))))))
