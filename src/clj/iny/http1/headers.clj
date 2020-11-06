(ns iny.http1.headers
  (:import [java.util
            Map$Entry]
           [clojure.lang
            PersistentArrayMap]
           [io.netty.handler.codec.http
            HttpHeaders
            HttpHeaderNames
            DefaultHttpHeaders]))

(defprotocol Headers
  (^HttpHeaders ->headers [_]))

(let [base-headers (doto (DefaultHttpHeaders. false)
                         (.add HttpHeaderNames/SERVER (str "iny/" version))
                         (.add HttpHeaderNames/CONTENT_TYPE "text/plain"))]
  (defn headers-with-date
    []
    (doto (.copy ^DefaultHttpHeaders base-headers)
          (.add HttpHeaderNames/DATE (date-header-value))))

  (extend-protocol Headers
    nil
    (->headers [_]
      (headers-with-date))

    PersistentArrayMap
    (->headers [^Iterable header-map]
      (let [headers ^HttpHeaders (headers-with-date)
            i (.iterator header-map)]
        (loop []
          (if (.hasNext i)
            (let [elem ^Map$Entry (.next i)]
              (.set headers
                    (-> elem .getKey .toString .toLowerCase)
                    (.getValue elem))
              (recur))))
        headers))))
