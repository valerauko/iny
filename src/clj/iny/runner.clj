(ns iny.runner
  (:require ; [clj-async-profiler.core :as prof]
            [jsonista.core :as json]
            [iny.http :as http])
  (:gen-class))

(defn my-handler [{uri :uri}]
  {:status 200
   :body (json/write-value-as-bytes {:message (str "Hello from " uri)})
   :headers {"content-type" "application/json"}})

(defn -main
  [& _]
  (http/run my-handler))
