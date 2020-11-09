(ns iny.http2.headers
  (:require [iny.meta :refer [version]]
            [iny.http.date :refer [date-header-value]])
  (:import [java.util
            Map$Entry]
           [clojure.lang
            PersistentArrayMap]
           [io.netty.handler.codec.http
            HttpHeaderNames]
           [io.netty.handler.codec.http2
            DefaultHttp2Headers]))

(defprotocol Headers
  (^io.netty.handler.codec.http2.DefaultHttp2Headers ->headers [_]))

;; DefaultHttp2Headers.copy returns a DefaultHeaders instance
(defn ^DefaultHttp2Headers headers-with-date
  []
  (doto (DefaultHttp2Headers. false)
        (.set HttpHeaderNames/SERVER (str "iny/" version))
        (.set HttpHeaderNames/CONTENT_TYPE "text/plain")
        (.set HttpHeaderNames/DATE (date-header-value))))

(extend-protocol Headers
  nil
  (->headers [_]
    (headers-with-date))

  PersistentArrayMap
  (->headers [^Iterable header-map]
    (let [headers (headers-with-date)
          i (.iterator header-map)]
      (loop []
        (if (.hasNext i)
          (let [elem ^Map$Entry (.next i)]
            (.set headers
                  (-> elem .getKey .toString .toLowerCase)
                  (.toString (.getValue elem)))
            (recur))))
      headers)))