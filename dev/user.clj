(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
            [clojure.test :refer [run-tests]]
            [clojure.repl :refer :all]
            [clojure.pprint :as pp]
            [clj-async-profiler.core :as prof]
            [jsonista.core :as json]
            [iny.server :as server]
            [pohjavirta.server :as poh]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]])
  (:import [io.netty.util
            ResourceLeakDetector
            ResourceLeakDetector$Level]))

(set-refresh-dirs "dev" "src/iny" "resources")

(defn run-test
  [test-var]
  (require :reload test-var)
  (run-tests test-var))

(defn my-handler [{:keys [uri params body] :as request}]
  (.reset ^java.io.InputStream body)
  {:status 200
   :body (json/write-value-as-bytes {:message (str "Hello from " uri)
                                     :params params
                                     :body (slurp body)})
   :headers {"content-type" "application/json"}})

(def stop-server nil)

(defn start-server
  [& _]
  ; (ResourceLeakDetector/setLevel ResourceLeakDetector$Level/SIMPLE)
  (def stop-server (server/server
                    (wrap-defaults my-handler (assoc-in
                                               api-defaults
                                               [:params :multipart]
                                               true))
                    )))

(defn start-poh
  [& _]
  (let [server (poh/create #'my-handler)]
    (def stop-server #(poh/stop server))
    (poh/start server)))
