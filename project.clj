(defproject social.kitsune/iny "0.0.1"
  :description "Performant Clojure HTTP server"
  :url "https://github.com/valerauko/iny"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.2-alpha1"]
                 [org.clojure/tools.logging "1.1.0"
                  :exclusions [org.clojure/clojure]]
                 [potemkin "0.4.5"]
                 [io.netty/netty-common "4.1.54.Final"]
                 [io.netty/netty-buffer "4.1.54.Final"]
                 [io.netty/netty-transport "4.1.54.Final"]
                 [io.netty/netty-codec "4.1.54.Final"]
                 [io.netty/netty-handler "4.1.54.Final"]
                 [io.netty/netty-codec-http "4.1.54.Final"]
                 [io.netty/netty-codec-http2 "4.1.54.Final"]]

  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]

  :aliases {"analyze" ["with-profile" "analyze" "do" ["run"]]}

  :profiles {:uberjar {:aot :all
                       :uberjar-name "iny.jar"}
             ; cf https://www.graalvm.org/docs/reference-manual/native-image/#tracing-agent
             :analyze {:aot :all
                       :jvm-opts ["-agentlib:native-image-agent=config-output-dir=./resources"]}
             :dev {:source-paths ["dev"]
                   :jvm-opts
                   ["-server"
                    "-Xms2G"
                    "-Xmx2G"
                    "-XX:+UseNUMA"
                    "-XX:MaxNewSize=100m"
                    "-XX:+UseG1GC"
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
                   [[org.clojure/test.check "1.1.0"]
                    [org.clojure/tools.namespace "1.0.0"]
                    [io.netty/netty-transport-native-epoll "4.1.54.Final"
                     :classifier "linux-x86_64"]
                    [ch.qos.logback/logback-classic "1.2.3"]
                    [criterium "0.4.5"]
                    [byte-streams "0.2.4"]
                    [ring/ring-defaults "0.3.2"]
                    [metosin/jsonista "0.2.6"]
                    [metosin/pohjavirta "0.0.1-alpha7"]
                    [com.clojure-goes-fast/clj-async-profiler "0.4.1"]]}})
