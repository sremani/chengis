(ns chengis.bench.config
  "Benchmark configuration defaults and override merging.")

(def defaults
  "Default benchmark configuration."
  {:overhead    {:iterations 100
                 :warm-up    5
                 :modes      [:executor-only :full-lifecycle]}
   :realistic   {:iterations 10
                 :warm-up    2
                 :repo-url   "https://github.com/clojure/tools.cli.git"
                 :repo-branch "master"}
   :throughput  {:concurrency-levels [1 2 4 8 16]
                 :builds-per-level   20
                 :warm-up            2}
   :system      {:workspace-root "/tmp/chengis-bench-workspaces"
                 :db-path        "/tmp/chengis-bench.db"
                 :results-dir    "benchmarks/results"}
   :resource-monitor {:sample-interval-ms 500}})

(defn merge-config
  "Deep-merge user overrides with defaults."
  [overrides]
  (merge-with merge defaults overrides))
