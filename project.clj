(defproject iny "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.2-alpha1"]
                 [org.clojure/tools.logging "1.1.0"
                  :exclusions [org.clojure/clojure]]
                 [potemkin "0.4.5"]
                 [metosin/jsonista "0.2.6"]
                 [io.netty/netty-common "4.1.53.Final"]
                 [io.netty/netty-buffer "4.1.53.Final"]
                 [io.netty/netty-transport "4.1.53.Final"]
                 [io.netty/netty-codec "4.1.53.Final"]
                 [io.netty/netty-handler "4.1.53.Final"]
                 [io.netty/netty-codec-http "4.1.53.Final"]
                 [io.netty/netty-codec-http2 "4.1.53.Final"]]

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]

  :repl-options {:init-ns iny.runner}
  :main ^:skip-aot iny.runner

  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]

  :aliases {"analyze" ["with-profile" "analyze" "do" ["run"]]}

  :profiles {:uberjar {:aot :all
                       :uberjar-name "iny.jar"}
             ; cf https://www.graalvm.org/docs/reference-manual/native-image/#tracing-agent
             :analyze {:aot :all
                       :jvm-opts ["-agentlib:native-image-agent=config-output-dir=./resources"]}
             :dev {:jvm-opts
                   ["-server"
                    "-Xms2G"
                    "-Xmx2G"
                    "-XX:+UseNUMA"
                    "-XX:+UseParallelGC"
                    "-XX:+AggressiveOpts"
                    "-Dvertx.disableMetrics=true"
                    "-Dvertx.threadChecks=false"
                    "-Dvertx.disableContextTimings=true"
                    "-Dvertx.disableTCCL=true"
                    "-Djdk.attach.allowAttachSelf"]
                   :global-vars
                   {*warn-on-reflection* true
                    *unchecked-math* :warn-on-boxed}
                   :dependencies
                   [[io.netty/netty-transport-native-epoll "4.1.53.Final"
                     :classifier "linux-x86_64"]
                    [ch.qos.logback/logback-classic "1.2.3"]
                    [criterium "0.4.5"]
                    ; [aleph "0.4.7-alpha5"]
                    [metosin/pohjavirta "0.0.1-alpha7"]
                    [com.clojure-goes-fast/clj-async-profiler "0.4.1"]]}})
