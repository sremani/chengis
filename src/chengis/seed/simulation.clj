(ns chengis.seed.simulation
  "Simulation data seeder for Chengis CI/CD.
   Populates the database with 90 days of realistic CI/CD activity.

   Usage:
     lein run -m chengis.seed.simulation        ;; default seed 42
     lein run -m chengis.seed.simulation 12345   ;; custom seed"
  (:require [chengis.config :as config]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [next.jdbc :as jdbc]
            [chengis.seed.sim.data :as data]
            [chengis.seed.sim.generators :as gen]
            [chengis.seed.sim.inserters :as ins])
  (:import [java.time LocalDate LocalDateTime]
           [java.util Random]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private simulation-days 90)
(def ^:private default-org-id "default-org")

;; ---------------------------------------------------------------------------
;; Counters (atom for tracking summary statistics)
;; ---------------------------------------------------------------------------

(defn- make-counters []
  (atom {:users 0 :jobs 0 :builds 0 :stages 0 :steps 0
         :logs 0 :webhooks 0 :audits 0 :approvals 0
         :analytics 0 :environments 0 :deployments 0 :promotions 0 :iac 0}))

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
;; Analytics aggregation seeding
;; ---------------------------------------------------------------------------

(defn- compute-build-analytics
  "Compute analytics aggregation from seeded builds for a given day and job."
  [^Random rng job-spec day-offset total-builds success-count]
  (let [^LocalDate date (gen/days-ago day-offset)
        period-start (gen/format-ts (.atTime date 0 0 0))
        period-end   (gen/format-ts (.atTime date 23 59 59))
        failure-count (- total-builds success-count)
        success-rate  (if (pos? total-builds) (double (/ success-count total-builds)) 0.0)
        ;; Generate plausible duration stats
        base-duration (case (:name job-spec)
                        "platform-api"    120.0
                        "web-frontend"     90.0
                        "payment-service" 150.0
                        "data-pipeline"   300.0
                        "ml-model-server" 480.0
                        "ios-app"         360.0
                        "infra-terraform" 180.0
                        "docs-site"        60.0
                        120.0)
        jitter  (* base-duration 0.3 (gen/next-double rng))
        avg-dur (+ base-duration jitter)]
    {:period-type   "daily"
     :period-start  period-start
     :period-end    period-end
     :total-builds  total-builds
     :success-count success-count
     :failure-count failure-count
     :aborted-count 0
     :success-rate  success-rate
     :avg-duration-s avg-dur
     :p50-duration-s (* avg-dur 0.85)
     :p90-duration-s (* avg-dur 1.5)
     :p99-duration-s (* avg-dur 2.2)
     :max-duration-s (* avg-dur 2.8)
     :computed-at    period-end}))

(defn- compute-stage-analytics
  "Compute stage-level analytics for a given day and stage."
  [^Random rng job-spec stage-name day-offset total-runs success-count]
  (let [^LocalDate date (gen/days-ago day-offset)
        period-start (gen/format-ts (.atTime date 0 0 0))
        period-end   (gen/format-ts (.atTime date 23 59 59))
        failure-count (- total-runs success-count)
        base-dur     (double (gen/generate-stage-duration-seconds rng stage-name))
        flakiness    (if (pos? total-runs)
                       (let [fail-rate (double (/ failure-count total-runs))]
                         (if (> fail-rate 0.3) (min 1.0 (* fail-rate 1.5)) fail-rate))
                       0.0)]
    {:stage-name    stage-name
     :period-type   "daily"
     :period-start  period-start
     :period-end    period-end
     :total-runs    total-runs
     :success-count success-count
     :failure-count failure-count
     :avg-duration-s base-dur
     :p90-duration-s (* base-dur 1.6)
     :max-duration-s (* base-dur 2.5)
     :flakiness-score flakiness
     :computed-at    period-end}))

(defn- seed-analytics!
  "Seed analytics aggregation tables from build data patterns."
  [ds ^Random rng counters job-ids]
  (println "  Seeding analytics aggregations...")
  (let [analytics-count (atom 0)]
    (doseq [job-spec data/jobs]
      (let [job-id (get job-ids (:name job-spec))]
        (doseq [day-offset (range simulation-days -1 -1)]
          (let [^LocalDate date (gen/days-ago day-offset)
                build-count (max 1 (gen/builds-for-day rng job-spec date))
                eff-rate    (gen/effective-success-rate job-spec day-offset)
                success-n   (int (* build-count eff-rate))
                ba          (compute-build-analytics rng job-spec day-offset build-count success-n)]
            ;; Insert build analytics
            (ins/insert-build-analytics! ds (assoc ba :org-id default-org-id :job-id job-id))
            (swap! analytics-count inc)
            ;; Insert stage analytics for each stage
            (doseq [stage-name (:stages job-spec)]
              (let [stage-success (int (* build-count (min 1.0 (+ eff-rate (* 0.05 (gen/next-double rng))))))
                    sa (compute-stage-analytics rng job-spec stage-name day-offset build-count stage-success)]
                (ins/insert-stage-analytics! ds (assoc sa :org-id default-org-id :job-id job-id))
                (swap! analytics-count inc)))))))
    (inc-counter-by! counters :analytics @analytics-count)))

;; ---------------------------------------------------------------------------
;; Environment & deployment seeding
;; ---------------------------------------------------------------------------

(def ^:private environments
  [{:name "Development"  :slug "dev"     :env-order 10 :description "Development environment"    :requires-approval false :auto-promote true}
   {:name "Staging"      :slug "staging" :env-order 20 :description "Staging/QA environment"     :requires-approval false :auto-promote false}
   {:name "Production"   :slug "prod"    :env-order 30 :description "Production environment"     :requires-approval true  :auto-promote false}])

(defn- seed-environments!
  "Seed environments. Returns a map of slug -> env-id."
  [ds counters]
  (println "  Seeding environments...")
  (let [created-at (gen/format-ts (.atTime ^LocalDate (gen/days-ago simulation-days) 7 0 0))]
    (reduce (fn [acc env-def]
              (let [id (ins/insert-environment! ds (assoc env-def :org-id default-org-id :created-at created-at))]
                (inc-counter! counters :environments)
                (assoc acc (:slug env-def) id)))
            {}
            environments)))

(defn- seed-deployments!
  "Seed deployments and promotions from successful builds."
  [ds ^Random rng counters env-ids]
  (println "  Seeding deployments and promotions...")
  (let [dev-id     (get env-ids "dev")
        staging-id (get env-ids "staging")
        prod-id    (get env-ids "prod")
        deploy-count (atom 0)
        promo-count  (atom 0)]
    ;; Query recent successful builds to create deployments from
    (let [builds (jdbc/execute! ds
                   ["SELECT id, job_id, created_at, completed_at FROM builds
                     WHERE status = 'success' ORDER BY created_at DESC LIMIT 200"])]
      (doseq [build builds]
        (let [build-id   (:builds/id build)
              created-at (:builds/created_at build)
              completed  (:builds/completed_at build)]
          ;; Every successful build deploys to dev
          (let [dep-id (ins/insert-deployment! ds
                         {:org-id default-org-id :environment-id dev-id :build-id build-id
                          :status "succeeded" :initiated-by nil
                          :started-at created-at :completed-at completed :created-at created-at})]
            (ins/insert-deployment-step! ds
              {:deployment-id dep-id :step-name "deploy" :step-order 1
               :status "succeeded" :started-at created-at :completed-at completed})
            (ins/insert-deployment-step! ds
              {:deployment-id dep-id :step-name "health-check" :step-order 2
               :status "succeeded" :started-at completed :completed-at completed})
            (swap! deploy-count + 3)) ;; 1 deployment + 2 steps

          ;; 60% promote to staging
          (when (< (gen/next-double rng) 0.6)
            (ins/insert-promotion! ds
              {:org-id default-org-id :build-id build-id
               :from-environment-id dev-id :to-environment-id staging-id
               :status "promoted" :promoted-by nil :promoted-at completed :created-at created-at})
            (ins/insert-deployment! ds
              {:org-id default-org-id :environment-id staging-id :build-id build-id
               :status "succeeded" :initiated-by nil
               :started-at completed :completed-at completed :created-at created-at})
            (swap! promo-count inc)
            (swap! deploy-count inc)

            ;; 30% of staging promotes to prod
            (when (< (gen/next-double rng) 0.3)
              (let [status (if (< (gen/next-double rng) 0.85) "promoted" "pending")]
                (ins/insert-promotion! ds
                  {:org-id default-org-id :build-id build-id
                   :from-environment-id staging-id :to-environment-id prod-id
                   :status status :promoted-by nil
                   :promoted-at (when (= status "promoted") completed)
                   :created-at created-at})
                (swap! promo-count inc)
                (when (= status "promoted")
                  (ins/insert-deployment! ds
                    {:org-id default-org-id :environment-id prod-id :build-id build-id
                     :status "succeeded" :initiated-by nil
                     :started-at completed :completed-at completed :created-at created-at})
                  (swap! deploy-count inc))))))))

    ;; Set current active artifacts for each environment (latest deployment)
    (doseq [[slug env-id] env-ids]
      (let [latest (first (jdbc/execute! ds
                            ["SELECT build_id, completed_at FROM deployments
                              WHERE environment_id = ? AND status = 'succeeded'
                              ORDER BY created_at DESC LIMIT 1" env-id]))]
        (when latest
          (ins/insert-environment-artifact! ds
            {:org-id default-org-id :environment-id env-id
             :build-id (:deployments/build_id latest)
             :status "active"
             :deployed-at (:deployments/completed_at latest)}))))

    (inc-counter-by! counters :deployments @deploy-count)
    (inc-counter-by! counters :promotions @promo-count)))

;; ---------------------------------------------------------------------------
;; IaC project seeding
;; ---------------------------------------------------------------------------

(def ^:private iac-project-defs
  [{:job-name "infra-terraform" :tool-type "terraform"       :working-dir "./infra"}
   {:job-name "platform-api"    :tool-type "cloudformation"  :working-dir "./deploy/cfn"}])

(defn- seed-iac-projects!
  "Seed IaC projects and plans."
  [ds ^Random rng counters job-ids]
  (println "  Seeding IaC projects and plans...")
  (let [iac-count (atom 0)
        created-at (gen/format-ts (.atTime ^LocalDate (gen/days-ago simulation-days) 8 0 0))]
    (doseq [proj-def iac-project-defs]
      (let [job-id     (get job-ids (:job-name proj-def))
            project-id (ins/insert-iac-project! ds
                         {:org-id default-org-id :job-id (or job-id (:job-name proj-def))
                          :tool-type (:tool-type proj-def)
                          :working-dir (:working-dir proj-def)
                          :created-at created-at})]
        (swap! iac-count inc)
        ;; Create plans for last 30 days
        (doseq [day-offset (range 30 -1 -1)]
          (let [^LocalDate date (gen/days-ago day-offset)
                num-plans (gen/next-int rng 3)] ;; 0-2 plans per day
            (dotimes [_ num-plans]
              (let [ts        (gen/generate-build-timestamp rng date "manual")
                    ts-str    (gen/format-ts ts)
                    action    (gen/pick rng ["plan" "plan" "plan" "apply" "apply" "preview"])
                    status    (gen/pick rng ["succeeded" "succeeded" "succeeded" "succeeded" "failed" "awaiting-approval"])
                    adds      (gen/next-int rng 8)
                    changes   (gen/next-int rng 5)
                    destroys  (gen/next-int rng 3)
                    duration  (+ 5000 (gen/next-int rng 120000))
                    plan-id   (ins/insert-iac-plan! ds
                                {:org-id default-org-id :project-id project-id
                                 :build-id nil :action action :status status
                                 :resources-add adds :resources-change changes
                                 :resources-destroy destroys
                                 :output (str action " completed: +" adds " ~" changes " -" destroys)
                                 :duration-ms duration :initiated-by nil
                                 :created-at ts-str})]
                (swap! iac-count inc)
                ;; Add cost estimate for ~50% of plans
                (when (< (gen/next-double rng) 0.5)
                  (let [monthly (+ 50.0 (* (gen/next-double rng) 2000.0))
                        hourly  (/ monthly 730.0)]
                    (ins/insert-iac-cost-estimate! ds
                      {:org-id default-org-id :plan-id plan-id
                       :total-monthly monthly :total-hourly hourly
                       :currency "USD" :created-at ts-str})
                    (swap! iac-count inc)))))))))
    (inc-counter-by! counters :iac @iac-count)))

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
    (println (str "  Analytics:      " (:analytics c)))
    (println (str "  Environments:   " (:environments c)))
    (println (str "  Deployments:    " (:deployments c)))
    (println (str "  Promotions:     " (:promotions c)))
    (println (str "  IaC records:    " (:iac c)))
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
        (seed-job-audit-events! ds counters job-ids user-ids)
        ;; Analytics, deploy, and IaC
        (seed-analytics! ds rng counters job-ids)
        (let [env-ids (seed-environments! ds counters)]
          (seed-deployments! ds rng counters env-ids))
        (seed-iac-projects! ds rng counters job-ids))

      (let [elapsed (- (System/currentTimeMillis) start-ms)]
        (print-summary counters elapsed))

      (println "Seed data is ready. Start the server with: lein run")
      (finally
        (conn/close-datasource! ds)))))
