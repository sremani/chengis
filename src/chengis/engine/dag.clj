(ns chengis.engine.dag
  "DAG (Directed Acyclic Graph) utilities for parallel stage execution.
   Stages can declare `:depends-on` to form a dependency graph.
   When any stage has `:depends-on`, the executor switches from sequential
   to DAG-based execution, running independent stages concurrently.")

(declare topological-sort)

;; ---------------------------------------------------------------------------
;; DAG construction and validation
;; ---------------------------------------------------------------------------

(defn has-dag?
  "Return true if any stage in the list has a :depends-on declaration.
   When false, the pipeline uses sequential execution (backward compat)."
  [stages]
  (boolean (some :depends-on stages)))

(defn build-dag
  "Convert a list of stage definitions into a DAG adjacency map.
   Returns {stage-name #{dependency-names}}.

   Validates:
   - All dependency references exist as stage names
   - No self-dependencies
   - No cycles (via topological sort)

   Throws ex-info on validation failure."
  [stages]
  (let [stage-names (set (map :stage-name stages))
        dag (reduce (fn [m stage]
                      (let [deps (set (or (:depends-on stage) []))]
                        (assoc m (:stage-name stage) deps)))
                    {}
                    stages)]
    ;; Validate: all deps must reference existing stages
    (doseq [[stage-name deps] dag]
      ;; No self-dependencies
      (when (contains? deps stage-name)
        (throw (ex-info (str "Stage '" stage-name "' depends on itself")
                        {:type :dag-self-dependency
                         :stage stage-name})))
      ;; All deps must exist
      (doseq [dep deps]
        (when-not (contains? stage-names dep)
          (throw (ex-info (str "Stage '" stage-name "' depends on unknown stage '" dep "'")
                          {:type :dag-unknown-dependency
                           :stage stage-name
                           :dependency dep
                           :known-stages stage-names})))))
    ;; Validate no cycles via topological sort (throws on cycle)
    (topological-sort dag)
    dag))

(defn topological-sort
  "Perform a topological sort using Kahn's algorithm.
   DAG format: {node #{dependencies}} where dependencies are nodes this node depends on.
   Returns an ordered list of stage names (dependencies before dependents).
   Throws ex-info if a cycle is detected."
  [dag]
  (let [all-nodes (set (keys dag))
        ;; In-degree = number of dependencies a node has
        in-degree (reduce-kv (fn [degrees node deps]
                               (assoc degrees node (count deps)))
                             {}
                             dag)
        ;; Build reverse map: for each node, which nodes depend on it?
        reverse-deps (reduce-kv (fn [m node deps]
                                  (reduce (fn [rm dep]
                                            (update rm dep (fnil conj #{}) node))
                                          m deps))
                                (zipmap all-nodes (repeat #{}))
                                dag)]
    (loop [queue (vec (filter #(zero? (get in-degree %)) all-nodes))
           result []
           remaining-degree in-degree
           visited #{}]
      (if (empty? queue)
        (if (= (count result) (count all-nodes))
          result
          (let [cycle-nodes (vec (remove visited all-nodes))]
            (throw (ex-info (str "Cycle detected in stage dependencies involving: "
                                 (pr-str cycle-nodes))
                            {:type :dag-cycle
                             :stages cycle-nodes}))))
        (let [node (first queue)
              rest-queue (subvec queue 1)
              ;; Get nodes that depend on this node
              dependents (get reverse-deps node #{})
              ;; Decrement in-degree for dependents
              new-degree (reduce (fn [d dep]
                                   (update d dep dec))
                                 remaining-degree
                                 dependents)
              new-visited (conj visited node)
              ;; Find newly ready nodes (in-degree = 0 and not yet visited or queued)
              queued-set (set rest-queue)
              newly-ready (filter (fn [n]
                                    (and (zero? (get new-degree n))
                                         (not (contains? new-visited n))
                                         (not (contains? queued-set n))))
                                  dependents)]
          (recur (into rest-queue newly-ready)
                 (conj result node)
                 new-degree
                 new-visited))))))

(defn ready-stages
  "Given a DAG and a set of completed stage names, return the set of
   stage names whose dependencies are ALL satisfied (all deps in completed set)
   and that are not themselves already completed."
  [dag completed]
  (set (filter (fn [stage-name]
                 (and (not (contains? completed stage-name))
                      (every? #(contains? completed %) (get dag stage-name))))
               (keys dag))))

(defn stage-by-name
  "Look up a stage definition by name from a list of stages."
  [stages stage-name]
  (first (filter #(= stage-name (:stage-name %)) stages)))
