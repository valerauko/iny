(ns user
  (:require [clojure.tools.logging :as log]
            [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
            [clojure.test :refer [run-tests]]
            [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [mount.core :refer [defstate start stop]]
            [clj-async-profiler.core :as prof]
            [jsonista.core :as json]
            [iny.server :as server]
            [iny.tls :refer [->ssl-opts]])
            ; [pohjavirta.server :as poh])
  (:import [iny Http3Client]
           [java.io InputStream]
           [java.nio.file Files Paths StandardCopyOption]
           [io.netty.util
            ResourceLeakDetector
            ResourceLeakDetector$Level]
           [io.netty.handler.ssl.util
            SelfSignedCertificate]))

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

(defn my-handler [{:keys [uri params ^InputStream body] :as request}]
  ; (log/debug (with-out-str (println) (pprint request)))
  (let [body-size
        (java.nio.file.Files/copy
         body
         (Paths/get "uploaded.jpg" (into-array String []))
         ^"[Ljava.nio.file.CopyOption;"
         (into-array StandardCopyOption
                     [StandardCopyOption/REPLACE_EXISTING]))]
    (.close body)
    {:status 200
     :body (json/write-value-as-bytes {:message (str "Hello from " uri)
                                       :params params
                                       :body (str body-size " bytes")})
     :headers {"content-type" "application/json"}}))

(defn http3
  []
  (Http3Client/main ^"[Ljava.lang.String" (into-array String [])))

(defn reload
  []
  (stop)
  (refresh)
  (start))

(defstate perf-files
  :start
  (prof/serve-files 8081)
  :stop
  (.stop ^com.sun.net.httpserver.HttpServer perf-files 1))

(defstate server
  :start
  ;; :ssl (->ssl-opts (SelfSignedCertificate.))
  (server/server my-handler :http2 true :http3 false)
  :stop
  (.close ^java.io.Closeable server))

; (defstate poh
;   :start
;   (poh/create #'my-handler)
;   :stop
;   (poh/stop poh))
