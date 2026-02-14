(ns chengis.seed.simulation
  "Simulation data seeder for Chengis CI/CD.
   Populates the database with 90 days of realistic CI/CD activity.

   Usage:
     lein run -m chengis.seed.simulation        ;; default seed 42
     lein run -m chengis.seed.simulation 12345   ;; custom seed"
  (:require [chengis.config :as config]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.seed.sim.data :as data]
            [chengis.seed.sim.generators :as gen]
            [chengis.seed.sim.inserters :as ins])
  (:import [java.time LocalDate LocalDateTime]
           [java.util Random]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private simulation-days 90)

;; ---------------------------------------------------------------------------
;; Counters (atom for tracking summary statistics)
;; ---------------------------------------------------------------------------

(defn- make-counters []
  (atom {:users 0 :jobs 0 :builds 0 :stages 0 :steps 0
         :logs 0 :webhooks 0 :audits 0 :approvals 0}))

(defn- inc-counter! [counters k]
  (swap! counters update k inc))

(defn- inc-counter-by! [counters k n]
  (swap! counters update k + n))

;; ---------------------------------------------------------------------------
;; User seeding
;; ---------------------------------------------------------------------------

(defn- seed-users!
  "Seed all simulation users. Returns a map of username -> user-id."
  [ds counters]
  (println "  Seeding users...")
  (let [created-at (gen/format-ts (.atTime ^LocalDate (gen/days-ago simulation-days) 8 0 0))]
    (reduce (fn [acc user-def]
              (let [id (ins/insert-user! ds (assoc user-def :created-at created-at))]
                (inc-counter! counters :users)
                (assoc acc (:username user-def) id)))
            {}
            data/users)))

;; ---------------------------------------------------------------------------
;; Job seeding
;; ---------------------------------------------------------------------------

(defn- build-pipeline-edn
  "Create a pipeline EDN definition from a job spec."
  [job-spec]
  {:pipeline-name (:name job-spec)
   :stages (mapv (fn [^String stage-name]
                   {:name stage-name
                    :steps [{:name (str "run-" (.toLowerCase (.replace stage-name " " "-")))
                             :command (str "echo 'Running " stage-name "'")}]})
                 (:stages job-spec))})

(defn- seed-jobs!
  "Seed all simulation jobs. Returns a map of job-name -> job-id."
  [ds counters]
  (println "  Seeding jobs...")
  (let [created-at (gen/format-ts (.atTime ^LocalDate (gen/days-ago simulation-days) 8 0 0))]
    (reduce (fn [acc job-spec]
              (let [pipeline (build-pipeline-edn job-spec)
                    id (ins/insert-job! ds {:name       (:name job-spec)
                                           :pipeline   pipeline
                                           :triggers   (:triggers job-spec)
                                           :created-at created-at})]
                (inc-counter! counters :jobs)
                (assoc acc (:name job-spec) id)))
            {}
            data/jobs)))

;; ---------------------------------------------------------------------------
;; Stage & step generation for a single build
;; ---------------------------------------------------------------------------

(defn- generate-stage-logs
  "Generate log entries for a stage."
  [^Random rng build-id stage-name stage-status ts-str]
  (let [templates (get data/stage-log-templates stage-name
                       [{:level "info" :msg (str "Running " stage-name "...")}
                        {:level "info" :msg (str stage-name " complete")}])]
    (if (= stage-status "failure")
      ;; On failure: show first template + failure log
      (let [fail-tmpl (get data/failure-log-templates stage-name
                           {:level "error" :msg (str stage-name " failed")})]
        [{:build-id build-id :timestamp ts-str
          :level "info" :source stage-name
          :message (:msg (first templates))}
         {:build-id build-id :timestamp ts-str
          :level (:level fail-tmpl) :source stage-name
          :message (:msg fail-tmpl)}])
      ;; On success: show all templates
      (mapv (fn [tmpl]
              {:build-id build-id :timestamp ts-str
               :level (:level tmpl) :source stage-name
               :message (:msg tmpl)})
            templates))))

(defn- seed-build-stages!
  "Seed stages, steps, and logs for a single build.
   Returns {:stage-count N :step-count N :log-count N}."
  [ds ^Random rng counters build-id stages fail-stage build-started-at]
  (let [failed? (some? fail-stage)]
    (loop [remaining  stages
           ^LocalDateTime current-ts build-started-at
           past-fail? false]
      (when-let [stage-name (first remaining)]
        (let [is-fail-stage (and failed? (= stage-name fail-stage))
              skipped?      past-fail?
              status        (cond skipped?      "skipped"
                                  is-fail-stage "failure"
                                  :else         "success")
              duration      (if skipped? 0 (gen/generate-stage-duration-seconds rng stage-name))
              ^LocalDateTime stage-end (.plusSeconds current-ts (long duration))
              started-str   (gen/format-ts current-ts)
              completed-str (gen/format-ts stage-end)]
          ;; Insert stage
          (ins/insert-stage! ds {:build-id     build-id
                                 :stage-name   stage-name
                                 :status       status
                                 :started-at   (when-not skipped? started-str)
                                 :completed-at (when-not skipped? completed-str)})
          (inc-counter! counters :stages)

          ;; Insert step (one step per stage for simplicity)
          (ins/insert-step! ds {:build-id     build-id
                                :stage-name   stage-name
                                :step-name    (str "run-" (.toLowerCase (.replace ^String stage-name " " "-")))
                                :status       status
                                :exit-code    (case status "success" 0 "failure" 1 nil)
                                :stdout       nil
                                :stderr       (when (= status "failure") "Process exited with code 1")
                                :started-at   (when-not skipped? started-str)
                                :completed-at (when-not skipped? completed-str)})
          (inc-counter! counters :steps)

          ;; Insert logs (skip logs for skipped stages)
          (when-not skipped?
            (let [logs (generate-stage-logs rng build-id stage-name status started-str)]
              (doseq [log logs]
                (ins/insert-log! ds log))
              (inc-counter-by! counters :logs (count logs))))

          (recur (rest remaining)
                 stage-end
                 (or past-fail? is-fail-stage)))))))

;; ---------------------------------------------------------------------------
;; Approval gate seeding
;; ---------------------------------------------------------------------------

(defn- maybe-seed-approval!
  "Seed an approval gate if the build deploys to production on the main branch."
  [ds ^Random rng counters build-id stages git-info build-status user-ids created-at-str]
  (let [has-prod-deploy (some #(or (= % "Deploy Prod") (= % "Apply")) stages)
        on-main         (= "main" (:branch git-info))
        succeeded       (= build-status "success")]
    (when (and has-prod-deploy on-main)
      (let [stage-name (if (some #(= % "Deploy Prod") stages) "Deploy Prod" "Apply")
            decision   (if succeeded
                         (gen/generate-approval-decision rng)
                         :pending)
            admin-id   (get user-ids (gen/pick rng data/admin-usernames))
            gate-data  {:build-id        build-id
                        :stage-name      stage-name
                        :status          (name decision)
                        :required-role   "admin"
                        :message         (gen/pick rng data/approval-messages)
                        :timeout-minutes 1440
                        :approved-by     (when (= decision :approved) admin-id)
                        :approved-at     (when (= decision :approved) created-at-str)
                        :rejected-by     (when (= decision :rejected) admin-id)
                        :rejected-at     (when (= decision :rejected) created-at-str)
                        :created-at      created-at-str}]
        (ins/insert-approval-gate! ds gate-data)
        (inc-counter! counters :approvals)))))

;; ---------------------------------------------------------------------------
;; Audit log seeding
;; ---------------------------------------------------------------------------

(defn- seed-build-audit!
  "Seed audit log entries for a build lifecycle."
  [ds counters build-id job-name build-status user-id username created-at-str]
  ;; build:create
  (ins/insert-audit! ds {:user-id       user-id
                         :username      username
                         :action        "build:create"
                         :resource-type "build"
                         :resource-id   build-id
                         :detail        {:job job-name :trigger "webhook"}
                         :ip-address    "10.0.1.50"
                         :timestamp     created-at-str})
  (inc-counter! counters :audits)

  ;; build:complete or build:fail
  (let [action (if (= build-status "success") "build:complete" "build:fail")]
    (ins/insert-audit! ds {:user-id       user-id
                           :username      username
                           :action        action
                           :resource-type "build"
                           :resource-id   build-id
                           :detail        {:status build-status}
                           :ip-address    "10.0.1.50"
                           :timestamp     created-at-str})
    (inc-counter! counters :audits)))

;; ---------------------------------------------------------------------------
;; Single build seeding
;; ---------------------------------------------------------------------------

(defn- seed-single-build!
  "Seed a single build with all related data (stages, steps, logs, webhooks, approvals, audit)."
  [ds ^Random rng counters job-spec job-id build-number user-ids day-offset ^LocalDate date]
  (let [trigger-type  (gen/generate-trigger-type rng)
        build-ts      (gen/generate-build-timestamp rng date trigger-type)
        succeeds?     (gen/build-succeeds? rng job-spec day-offset)
        fail-stage    (when-not succeeds? (gen/pick-failure-stage rng job-spec))
        stages        (:stages job-spec)
        git-info      (gen/generate-git-info rng)
        created-at    (gen/format-ts build-ts)
        ;; Calculate build end time by summing stage durations
        total-secs    (reduce + (map (fn [s]
                                       (if (and fail-stage
                                                (let [idx-fail (.indexOf ^java.util.List stages fail-stage)
                                                      idx-s    (.indexOf ^java.util.List stages s)]
                                                  (> idx-s idx-fail)))
                                         0
                                         (gen/generate-stage-duration-seconds rng s)))
                                     stages))
        completed-ts  (.plusSeconds ^LocalDateTime build-ts (long total-secs))
        build-status  (if succeeds? "success" "failure")
        author        (:author git-info)
        user-id       (get user-ids author (get user-ids "bot-ci"))
        build-id      (ins/insert-build! ds
                        {:job-id           job-id
                         :build-number     build-number
                         :status           build-status
                         :trigger-type     trigger-type
                         :started-at       created-at
                         :completed-at     (gen/format-ts completed-ts)
                         :created-at       created-at
                         :git-branch       (:branch git-info)
                         :git-commit       (:commit git-info)
                         :git-commit-short (:commit-short git-info)
                         :git-author       (:author git-info)
                         :git-message      (:message git-info)})]
    (inc-counter! counters :builds)

    ;; Seed stages, steps, logs
    (seed-build-stages! ds rng counters build-id stages fail-stage build-ts)

    ;; Seed webhook event (for webhook-triggered builds)
    (when (= trigger-type "webhook")
      (ins/insert-webhook-event! ds
        (assoc (gen/generate-webhook-event rng git-info (:name job-spec))
               :created-at created-at))
      (inc-counter! counters :webhooks))

    ;; Seed approval gate (for production deploys on main)
    (maybe-seed-approval! ds rng counters build-id stages git-info build-status user-ids created-at)

    ;; Seed audit trail
    (seed-build-audit! ds counters build-id (:name job-spec) build-status user-id author created-at)))

;; ---------------------------------------------------------------------------
;; Main build loop
;; ---------------------------------------------------------------------------

(defn- seed-builds!
  "Seed all builds across all jobs for the simulation period."
  [ds ^Random rng counters job-ids user-ids]
  (println "  Seeding builds (this may take a moment)...")
  (doseq [job-spec data/jobs]
    (let [job-id    (get job-ids (:name job-spec))
          job-name  (:name job-spec)]
      (printf "    %s..." job-name)
      (flush)
      (let [build-num (atom 0)]
        (doseq [day-offset (range simulation-days -1 -1)]
          (let [date        (gen/days-ago day-offset)
                build-count (gen/builds-for-day rng job-spec date)]
            (dotimes [_ build-count]
              (swap! build-num inc)
              (seed-single-build! ds rng counters job-spec job-id @build-num
                                  user-ids day-offset date)))))
      (println " done"))))

;; ---------------------------------------------------------------------------
;; Seed user audit events (logins)
;; ---------------------------------------------------------------------------

(defn- seed-user-audit-events!
  "Seed some user login/logout audit events spread over the simulation period."
  [ds ^Random rng counters user-ids]
  (println "  Seeding user audit events...")
  (doseq [day-offset (range simulation-days -1 -1)]
    (let [date (gen/days-ago day-offset)]
      ;; ~2-4 login events per day
      (dotimes [_ (+ 2 (gen/next-int rng 3))]
        (let [username (gen/pick rng (keys user-ids))
              user-id  (get user-ids username)
              ts       (gen/format-ts (gen/generate-build-timestamp rng date "manual"))]
          (ins/insert-audit! ds {:user-id       user-id
                                 :username      username
                                 :action        "user:login"
                                 :resource-type "user"
                                 :resource-id   user-id
                                 :detail        {:method "password"}
                                 :ip-address    (str "10.0.1." (+ 10 (gen/next-int rng 240)))
                                 :timestamp     ts})
          (inc-counter! counters :audits))))))

;; ---------------------------------------------------------------------------
;; Seed job audit events (creation)
;; ---------------------------------------------------------------------------

(defn- seed-job-audit-events!
  "Seed job creation audit events."
  [ds counters job-ids user-ids]
  (println "  Seeding job audit events...")
  (let [admin-id  (get user-ids "admin")
        created-at (gen/format-ts (.atTime ^LocalDate (gen/days-ago simulation-days) 8 30 0))]
    (doseq [[job-name job-id] job-ids]
      (ins/insert-audit! ds {:user-id       admin-id
                             :username      "admin"
                             :action        "job:create"
                             :resource-type "job"
                             :resource-id   job-id
                             :detail        {:name job-name}
                             :ip-address    "10.0.1.10"
                             :timestamp     created-at})
      (inc-counter! counters :audits))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- print-summary [counters elapsed-ms]
  (let [c @counters]
    (println)
    (println "=== Simulation Seeding Complete ===")
    (println (format "  Time:           %.1fs" (/ elapsed-ms 1000.0)))
    (println (str "  Users:          " (:users c)))
    (println (str "  Jobs:           " (:jobs c)))
    (println (str "  Builds:         " (:builds c)))
    (println (str "  Build Stages:   " (:stages c)))
    (println (str "  Build Steps:    " (:steps c)))
    (println (str "  Build Logs:     " (:logs c)))
    (println (str "  Webhook Events: " (:webhooks c)))
    (println (str "  Audit Logs:     " (:audits c)))
    (println (str "  Approval Gates: " (:approvals c)))
    (println (str "  Total records:  "
                  (reduce + (vals c))))
    (println)))

(defn -main
  "Entry point for simulation seeder.
   Optional argument: PRNG seed (default: 42)."
  [& args]
  (let [seed     (if (first args) (Long/parseLong (first args)) 42)
        rng      (Random. seed)
        cfg      (config/load-config)
        db-cfg   (:database cfg)
        _        (println (str "Simulation Seeder (seed=" seed ")"))
        _        (println (str "Database: " (if (= "postgresql" (:type db-cfg))
                                              (str "PostgreSQL " (:host db-cfg) ":" (:port db-cfg) "/" (:dbname db-cfg))
                                              (str "SQLite " (:path db-cfg)))))
        _        (println)
        _        (println "Running migrations...")
        _        (migrate/migrate! db-cfg)
        ds       (conn/create-datasource db-cfg)
        _        (conn/test-connection ds)
        counters (make-counters)
        start-ms (System/currentTimeMillis)]
    (try
      (println "Clearing existing data...")
      (ins/clear-all-tables! ds)
      (println)
      (println "Seeding simulation data...")

      (let [user-ids (seed-users! ds counters)
            job-ids  (seed-jobs! ds counters)]
        (seed-builds! ds rng counters job-ids user-ids)
        (seed-user-audit-events! ds rng counters user-ids)
        (seed-job-audit-events! ds counters job-ids user-ids))

      (let [elapsed (- (System/currentTimeMillis) start-ms)]
        (print-summary counters elapsed))

      (println "Seed data is ready. Start the server with: lein run")
      (finally
        (conn/close-datasource! ds)))))
