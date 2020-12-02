(ns iny.http2.headers
  (:require [iny.meta :refer [version]]
            [iny.http.date :refer [date-header-value]])
  (:import [java.util
            Map$Entry]
           [clojure.lang
            PersistentArrayMap]
           [io.netty.util
            AsciiString]
           [io.netty.handler.codec.http
            HttpHeaderNames]
           [io.netty.handler.codec.http2
            DefaultHttp2Headers]))

(defprotocol Headers
  (^io.netty.handler.codec.http2.DefaultHttp2Headers ->headers [_]))

;; DefaultHttp2Headers.copy returns a DefaultHeaders instance
(let [ver-str (AsciiString. (str "iny/" version))]
  (defn ^DefaultHttp2Headers headers-with-date
    []
    (doto (DefaultHttp2Headers. false)
          (.set HttpHeaderNames/SERVER ver-str)
          (.set HttpHeaderNames/CONTENT_TYPE "text/plain")
          (.set HttpHeaderNames/DATE (date-header-value)))))

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
                  (-> elem .getKey (name) .toString .toLowerCase)
                  (.toString (.getValue elem)))
            (recur))))
      headers)))

(defn headers->map
  [headers]
  (persistent!
   (reduce-kv
    (fn [aggr ^AsciiString k ^AsciiString v]
      (assoc! aggr (.toString (.toLowerCase k)) (.toString v)))
    (transient {})
    (into {} headers))))
