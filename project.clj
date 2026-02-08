(defproject chengis "0.1.0-SNAPSHOT"
  :description "Chengis — a CI/CD engine written in Clojure"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/core.async "1.6.681"]
                 [org.clojure/tools.cli "1.1.230"]
                 [babashka/process "0.5.22"]
                 [com.github.seancorfield/next.jdbc "1.3.939"]
                 [com.github.seancorfield/honeysql "2.6.1147"]
                 [org.xerial/sqlite-jdbc "3.45.1.0"]
                 [migratus/migratus "1.5.6"]
                 [com.taoensso/timbre "6.6.1"]
                 [jarohen/chime "0.3.3"]
                 [org.slf4j/slf4j-nop "2.0.9"]
                 [http-kit/http-kit "2.8.0"]
                 [metosin/reitit-ring "0.7.2"]
                 [ring/ring-defaults "0.5.0"]
                 [hiccup/hiccup "2.0.0-RC3"]
                 [org.clojure/data.json "2.5.0"]
                 [clj-commons/clj-yaml "1.0.29"]
                 [buddy/buddy-hashers "2.0.167"]
                 [buddy/buddy-sign "3.5.351"]
                 [clj-commons/iapetos "0.1.14"]
                 [io.prometheus/simpleclient_hotspot "0.16.0"]
                 [viesti/timbre-json-appender "0.2.14"]
                 [com.sun.mail/javax.mail "1.6.2"]]
  :jvm-opts ["--enable-native-access=ALL-UNNAMED"]
  :main chengis.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                        :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:dependencies [[org.clojure/test.check "1.1.1"]]}
             :bench {:source-paths ["benchmarks/src"]
                     :main chengis.bench.runner
                     :jvm-opts ["--enable-native-access=ALL-UNNAMED"
                                "-Xmx2g" "-Xms512m" "-server"]}})
