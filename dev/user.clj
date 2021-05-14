(ns user
  (:require [clojure.tools.logging :as log]
            [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
            [clojure.test :refer [run-tests]]
            [clojure.repl :refer :all]
            [clojure.pprint :as pp]
            [mount.core :refer [defstate start stop]]
            [clj-async-profiler.core :as prof]
            [jsonista.core :as json]
            [iny.server :as server])
            ; [pohjavirta.server :as poh])
  (:import [io.netty.util
            ResourceLeakDetector
            ResourceLeakDetector$Level]
           [io.netty.buffer
            ByteBufInputStream]))

(set-refresh-dirs "dev" "src/iny" "resources")
(ResourceLeakDetector/setLevel ResourceLeakDetector$Level/DISABLED)

(defn run-test
  [test-var]
  (if-let [ns-str (namespace test-var)]
    (let [ns-sym (symbol ns-str)
          name-sym (symbol (name test-var))]
      (remove-ns ns-sym)
      (require :reload ns-sym)
      (doseq [test-sym (keys (ns-interns ns-sym))]
        (when-not (= test-sym name-sym)
          (ns-unmap ns-sym test-sym)))
      (run-tests ns-sym))
    (do
      (remove-ns test-var)
      (require :reload test-var)
      (run-tests test-var))))

(defn my-handler [{:keys [uri params body] :as request}]
  (let [contents (slurp body)]
    {:status 200
     :body (json/write-value-as-bytes {:message (str "Hello from " uri " using "
                                                     (iny.native/socket-chan))
                                       :params params
                                       :body contents})
     :headers {"content-type" "application/json"}}))

(defn reload
  []
  (stop)
  (refresh)
  (start))

(defstate agents
  :start :noop
  :stop (shutdown-agents))

(defstate perf-files
  :start
  (prof/serve-files 8081)
  :stop
  (.stop ^com.sun.net.httpserver.HttpServer perf-files 1))

(defstate server
  :start
  (server/server my-handler)
  :stop
  (.close server))

; (defstate poh
;   :start
;   (poh/create #'my-handler)
;   :stop
;   (poh/stop poh))
