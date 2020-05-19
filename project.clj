(defproject iny "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [io.netty/netty-common "4.1.50.Final"]
                 [io.netty/netty-buffer "4.1.50.Final"]
                 [io.netty/netty-transport "4.1.50.Final"]
                 [io.netty/netty-transport-native-epoll "4.1.50.Final"
                  :classifier "linux-x86_64"]
                 [io.netty/netty-codec "4.1.50.Final"]
                 [io.netty/netty-handler "4.1.50.Final"]
                 [io.netty/netty-codec-http "4.1.50.Final"]]
  :repl-options {:init-ns iny.runner}
  :main ^:skip-aot iny.runner
  :jvm-opts ["-server"
             "-Xms2G"
             "-Xmx2G"
             "-XX:+UseNUMA"
             "-XX:+UseParallelGC"
             "-XX:+AggressiveOpts"
             "-Dvertx.disableMetrics=true"
             "-Dvertx.threadChecks=false"
             "-Dvertx.disableContextTimings=true"
             "-Dvertx.disableTCCL=true"
             "-Djdk.attach.allowAttachSelf"
             "-Dclojure.compiler.direct-linking=true"]
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies
                   [[criterium "0.4.5"]
                    [metosin/jsonista "0.2.6"]
                    [aleph "0.4.7-alpha5"]
                    [metosin/pohjavirta "0.0.1-alpha7"]
                    [com.clojure-goes-fast/clj-async-profiler "0.4.1"]]}})
