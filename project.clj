(defproject social.kitsune/iny "0.1.5"
  :description "Performant Clojure HTTP server"
  :url "https://github.com/valerauko/iny"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/tools.logging "1.1.0"
                  :exclusions [org.clojure/clojure]]
                 [potemkin "0.4.5"]
                 [io.netty/netty-common "4.1.64.Final"]
                 [io.netty/netty-buffer "4.1.64.Final"]
                 [io.netty/netty-transport "4.1.64.Final"]
                 [io.netty/netty-codec "4.1.64.Final"]
                 [io.netty/netty-handler "4.1.64.Final"]
                 [io.netty/netty-codec-http "4.1.64.Final"]
                 [io.netty/netty-codec-http2 "4.1.64.Final"]
                 [io.netty.incubator/netty-incubator-codec-quic "0.0.12.Final"
                  :classifier "linux-x86_64"]
                 [io.netty.incubator/netty-incubator-codec-http3 "0.0.3.Final"
                  :exclusions [io.netty.incubator/netty-incubator-codec-quic]]]

  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]
  :java-source-paths ["src/java"]

  :aliases {"analyze" ["with-profile" "analyze" "do" ["run"]]}

  :profiles {:uberjar {:aot :all}
             ; cf https://www.graalvm.org/docs/reference-manual/native-image/#tracing-agent
             :analyze {:aot :all
                       :jvm-opts ["-agentlib:native-image-agent=config-output-dir=./resources"]}
             :dev {:source-paths ["dev"]
                   :plugins [[lein-ancient "0.7.0"]]
                   :jvm-opts
                   ["-server"
                    "-Xms2G"
                    "-Xmx2G"
                    "-XX:+UseNUMA"
                    "-XX:MaxNewSize=100m"
                    "-XX:+UseG1GC"
                    "-Dvertx.disableMetrics=true"
                    "-Dvertx.threadChecks=false"
                    "-Dvertx.disableContextTimings=true"
                    "-Dvertx.disableTCCL=true"
                    "-javaagent:vendor/prometheus.jar=8082:vendor/prometheus.yaml"
                    "-Djdk.attach.allowAttachSelf"]
                   :global-vars
                   {*warn-on-reflection* true
                    *unchecked-math* :warn-on-boxed}
                   :dependencies
                   [[org.clojure/test.check "1.1.0"]
                    [org.clojure/tools.namespace "1.1.0"]
                    [io.netty.incubator/netty-incubator-transport-native-io_uring "0.0.5.Final"
                     :exclusions [io.netty/netty-transport-native-unix-common]
                     :classifier "linux-x86_64"]
                    [io.netty/netty-transport-native-epoll "4.1.64.Final"
                     :classifier "linux-x86_64"]
                    [io.netty/netty-transport-native-kqueue "4.1.64.Final"]
                    [mount "0.1.16"]
                    [metosin/jsonista "0.3.3"]
                    [ch.qos.logback/logback-classic "1.2.3"]
                    [criterium "0.4.6"]
                    ; [metosin/pohjavirta "0.0.1-alpha7"]
                    [com.clojure-goes-fast/clj-async-profiler "0.5.0"]]}})
