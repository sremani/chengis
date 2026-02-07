# Pipeline Examples

This guide walks through the example pipelines included with Chengis, from simple "Hello World" to real-world CI builds.

## Table of Contents

- [1. Hello World (example.clj)](#1-hello-world)
- [2. Git Integration (hello-git.clj)](#2-git-integration)
- [3. Java CI with Maven (java-demo.clj)](#3-java-ci-with-maven)
- [4. C# CI with .NET (dotnet-demo.clj)](#4-c-ci-with-net)
- [5. Self-Hosting Build (chengis.clj)](#5-self-hosting-build)
- [6. Writing Your Own Pipeline](#6-writing-your-own-pipeline)
- [7. Pipeline as Code (Chengisfile)](#7-pipeline-as-code)

---

## 1. Hello World

**File:** `pipelines/example.clj`

The simplest pipeline demonstrating stages, steps, and parallel execution.

```clojure
(require '[chengis.dsl.core :refer [defpipeline stage step parallel sh]])

(defpipeline example
  {:description "Example Chengis pipeline"}

  (stage "Hello"
    (step "Greet" (sh "echo 'Hello from Chengis!'")))

  (stage "Test"
    (parallel
      (step "Fast" (sh "echo 'fast test' && sleep 0.5"))
      (step "Slow" (sh "echo 'slow test' && sleep 1"))))

  (stage "Done"
    (step "Finish" (sh "echo 'Build complete!'"))))
```

**What it demonstrates:**
- Basic `defpipeline` structure
- Sequential stages (Hello -> Test -> Done)
- Parallel steps within a stage (Fast and Slow run concurrently)
- Shell commands with `sh`

**Run it:**
```bash
lein run -- job create pipelines/example.clj
lein run -- build trigger example
```

---

## 2. Git Integration

**File:** `pipelines/hello-git.clj`

Demonstrates cloning a Git repository and using Git metadata environment variables.

```clojure
(require '[chengis.dsl.core :refer [defpipeline stage step sh]])

(defpipeline hello-git
  {:description "Test pipeline — clones a public repo and verifies git info"
   :source {:type :git
            :url "https://github.com/octocat/Hello-World.git"
            :branch "master"
            :depth 1}}

  (stage "Verify"
    (step "Check files"
      (sh "ls -la"))
    (step "Git info"
      (sh "echo Branch=$GIT_BRANCH Commit=$GIT_COMMIT_SHORT Author=$GIT_AUTHOR"))
    (step "Git log"
      (sh "git log --oneline -3"))))
```

**What it demonstrates:**
- The `:source` option for automatic Git checkout
- `:depth 1` for shallow clones (faster)
- Built-in Git environment variables: `GIT_BRANCH`, `GIT_COMMIT`, `GIT_COMMIT_SHORT`, `GIT_AUTHOR`, `GIT_EMAIL`, `GIT_MESSAGE`
- Steps run inside the cloned workspace

**Available Git environment variables:**

| Variable | Description | Example |
|----------|-------------|---------|
| `GIT_COMMIT` | Full SHA | `a9e70e7d2f...` |
| `GIT_COMMIT_SHORT` | Short SHA | `a9e70e7` |
| `GIT_BRANCH` | Branch name | `main` |
| `GIT_AUTHOR` | Commit author name | `John Doe` |
| `GIT_EMAIL` | Commit author email | `john@example.com` |
| `GIT_MESSAGE` | Commit message subject | `Fix build script` |

---

## 3. Java CI with Maven

**File:** `pipelines/java-demo.clj`

A real-world pipeline that builds and tests a Java project from GitHub.

```clojure
(require '[chengis.dsl.core :refer [defpipeline stage step sh artifacts]])

(defpipeline java-demo
  {:description "Java CI — JUnit5 Samples (Maven)"
   :source {:type :git
            :url "https://github.com/junit-team/junit5-samples.git"
            :branch "main"
            :depth 1}}

  (stage "Build"
    (step "Compile"
      (sh "cd junit-jupiter-starter-maven && mvn compile -q")))

  (stage "Test"
    (step "Unit Tests"
      (sh "cd junit-jupiter-starter-maven && mvn test")))

  (artifacts "junit-jupiter-starter-maven/target/surefire-reports/*.xml"))
```

**What it demonstrates:**
- Building a real Java project from GitHub
- Maven compile and test stages
- Artifact collection: Surefire XML test reports
- Glob patterns for artifact matching

**Prerequisites:** Maven 3.x must be installed.

**Actual build output:**
```
Build #3 — SUCCESS (8.7 sec)

  Stage: Build — Compile          SUCCESS  2.1s
  Stage: Test — Unit Tests        SUCCESS  5.3s
    Tests run: 5, Failures: 0, Errors: 0

  Artifacts:
    TEST-com.example.project.CalculatorTests.xml  (7.5 KB)
```

---

## 4. C# CI with .NET

**File:** `pipelines/dotnet-demo.clj`

Builds and tests FluentValidation, a popular .NET validation library with 865 tests.

```clojure
(require '[chengis.dsl.core :refer [defpipeline stage step sh artifacts]])

(defpipeline dotnet-demo
  {:description "C# CI — FluentValidation (.NET 9)"
   :source {:type :git
            :url "https://github.com/FluentValidation/FluentValidation.git"
            :branch "main"
            :depth 1}}

  (stage "Build"
    (step "Restore & Compile"
      (sh "dotnet build FluentValidation.sln -c Release -v minimal")))

  (stage "Test"
    (step "Unit Tests"
      (sh "dotnet test FluentValidation.sln --framework net9.0 --no-build -c Release --logger trx")))

  (artifacts "src/FluentValidation/bin/Release/net8.0/FluentValidation.dll"
             "src/FluentValidation.Tests/TestResults/*.trx"))
```

**What it demonstrates:**
- .NET/C# project builds work seamlessly
- `dotnet build` and `dotnet test` integration
- TRX test result logging
- Multiple artifact patterns (DLL + test results)

**Prerequisites:** .NET SDK 9.x must be installed.

**Actual build output:**
```
Build #1 — SUCCESS (8.3 sec)

  Stage: Build — Restore & Compile    SUCCESS  3.1s
    0 Warning(s), 0 Error(s)

  Stage: Test — Unit Tests            SUCCESS  4.5s
    Passed: 865, Failed: 0, Skipped: 1

  Artifacts:
    FluentValidation.dll               (519.5 KB)
    TestResults.trx                    (1.2 MB)
```

---

## 5. Self-Hosting Build

**File:** `pipelines/chengis.clj`

Chengis builds itself! Runs the test suite and produces an uberjar.

```clojure
(require '[chengis.dsl.core :refer [defpipeline stage step sh]])

(defpipeline chengis
  {:description "Self-hosting pipeline — Chengis builds itself"}

  (stage "Checkout"
    (step "Copy source"
      (sh "cp -r /path/to/chengis/src ... .")))

  (stage "Test"
    (step "Run tests"
      (sh "lein test" :timeout 120000)))

  (stage "Build"
    (step "Uberjar"
      (sh "lein uberjar" :timeout 300000)))

  (stage "Verify"
    (step "Check artifact"
      (sh "ls -lh target/uberjar/chengis-*-standalone.jar"))
    (step "Smoke test"
      (sh "java -jar target/uberjar/chengis-*-standalone.jar 2>&1 | head -5; exit 0"
        :timeout 30000))))
```

**What it demonstrates:**
- Custom timeouts per step (`:timeout` in milliseconds)
- Multi-stage pipeline: checkout, test, build, verify
- Producing and verifying a deployable artifact

---

## 6. Writing Your Own Pipeline

### Step 1: Create a pipeline file

```clojure
(require '[chengis.dsl.core :refer [defpipeline stage step parallel sh
                                     post always on-success on-failure
                                     artifacts notify
                                     when-branch when-param]])

(defpipeline my-project
  {:description "My project CI/CD pipeline"

   ;; Optional: Git source
   :source {:type :git
            :url "https://github.com/myorg/myproject.git"
            :branch "main"
            :depth 1}

   ;; Optional: Build parameters
   :parameters [{:name "environment"
                 :type :choice
                 :choices ["dev" "staging" "production"]
                 :default "dev"}]}

  ;; Stages run sequentially
  (stage "Build"
    (step "Install deps" (sh "npm install"))
    (step "Compile"      (sh "npm run build")))

  ;; Steps in a parallel block run concurrently
  (stage "Quality"
    (parallel
      (step "Unit Tests"   (sh "npm test"))
      (step "Lint"         (sh "npm run lint"))
      (step "Type Check"   (sh "npm run typecheck"))))

  ;; Conditional execution
  (stage "Deploy"
    (when-branch "main"
      (step "Deploy Prod" (sh "./deploy.sh" :env {"ENV" "production"})))
    (when-param :environment "staging"
      (step "Deploy Staging" (sh "./deploy.sh" :env {"ENV" "staging"}))))

  ;; Post-build actions (never affect build status)
  (post
    (always
      (step "Cleanup" (sh "rm -rf node_modules/.cache")))
    (on-success
      (step "Tag Release" (sh "git tag v$(date +%Y%m%d)")))
    (on-failure
      (step "Debug Info" (sh "cat npm-debug.log || true"))))

  ;; Artifact collection
  (artifacts "dist/**/*.js"
             "coverage/lcov.info"
             "test-results/*.xml")

  ;; Notifications
  (notify :console {}))
```

### Step 2: Register and run

```bash
lein run -- job create pipelines/my-project.clj
lein run -- build trigger my-project
```

### DSL Quick Reference

| Function | Purpose | Example |
|----------|---------|---------|
| `defpipeline` | Define a named pipeline | `(defpipeline my-app ...)` |
| `stage` | Group of steps (runs sequentially) | `(stage "Build" ...)` |
| `step` | Named action | `(step "Compile" (sh "make"))` |
| `sh` | Shell command | `(sh "echo hi" :timeout 60000)` |
| `parallel` | Run steps concurrently | `(parallel (step ...) (step ...))` |
| `when-branch` | Branch condition | `(when-branch "main" (step ...))` |
| `when-param` | Parameter condition | `(when-param :env "prod" (step ...))` |
| `post` | Post-build actions | `(post (always ...) (on-success ...))` |
| `always` | Runs after every build | `(always (step "Cleanup" ...))` |
| `on-success` | Runs after success only | `(on-success (step "Deploy" ...))` |
| `on-failure` | Runs after failure only | `(on-failure (step "Alert" ...))` |
| `artifacts` | Collect files by glob | `(artifacts "target/*.jar")` |
| `notify` | Send notifications | `(notify :slack {:webhook-url "..."})` |

---

## 7. Pipeline as Code

Instead of defining pipelines on the server, you can commit a `Chengisfile` directly in your repository.

### Format

Create a file named `Chengisfile` in your repository root:

```clojure
{:description "My App CI"

 :stages
 [{:name "Build"
   :steps [{:name "Install" :run "npm install"}
           {:name "Compile" :run "npm run build"}]}

  {:name "Test"
   :parallel true
   :steps [{:name "Unit"   :run "npm test"}
           {:name "Lint"   :run "npm run lint"}
           {:name "E2E"    :run "npm run e2e"}]}]

 :post
 {:always     [{:name "Cleanup" :run "rm -rf .cache"}]
  :on-success [{:name "Report"  :run "echo SUCCESS"}]
  :on-failure [{:name "Debug"   :run "cat error.log"}]}

 :artifacts
 ["dist/**/*.js" "coverage/**/*.html"]

 :notify
 [{:type :console}]}
```

### How It Works

1. You define a job on the server with a `:source` Git URL
2. When a build triggers, Chengis clones the repo
3. Chengis checks for a `Chengisfile` in the workspace root
4. If found, it **overrides** the server-side pipeline definition
5. The build runs using the in-repo pipeline

This is conceptually identical to how Jenkins uses `Jenkinsfile` or GitHub Actions uses `.github/workflows/`.

### Chengisfile vs Server Pipeline

| Feature | Chengisfile | Server Pipeline |
|---------|------------|-----------------|
| Location | In your repo root | `pipelines/*.clj` on server |
| Format | EDN (data only) | Clojure DSL (code) |
| Versioned with code | Yes | No |
| Requires server access | No | Yes |
| Full DSL features | Subset (no macros) | Complete |

### Step Options in Chengisfile

```clojure
{:name "Build"
 :run "make build"                    ;; Command (required)
 :dir "frontend"                      ;; Working directory (optional)
 :timeout 300000                      ;; Timeout in ms (optional)
 :env {"NODE_ENV" "production"}       ;; Extra env vars (optional)
 :condition {:type :branch            ;; Conditional execution (optional)
             :value "main"}}
```
