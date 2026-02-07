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
