(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
            [clojure.test :refer [run-tests]]
            [clojure.repl :refer :all]
            [clojure.pprint :as pp]
            [clj-async-profiler.core :as prof]
            [jsonista.core :as json]
            [iny.server :as server]
            [pohjavirta.server :as poh]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(set-refresh-dirs "dev/clj" "src" "resources")

(defn run-test
  [test-var]
  (require :reload test-var)
  (run-tests test-var))

(defn my-handler [{:keys [uri]}]
  {:status 200
   :body (json/write-value-as-bytes {:message (str "Hello from " uri)})
   :headers {"content-type" "application/json"}})

(def stop-server nil)

(defn start-server
  [& _]
  (def stop-server (server/server my-handler)))

(defn start-poh
  [& _]
  (let [server (poh/create #'my-handler)]
    (def stop-server #(poh/stop server))
    (poh/start server)))
