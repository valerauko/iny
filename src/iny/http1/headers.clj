(ns iny.http1.headers
  (:require [iny.meta :refer [version]]
            [iny.http.date :refer [date-header-value]])
  (:import [java.util
            Map$Entry]
           [clojure.lang
            PersistentArrayMap]
           [io.netty.handler.codec.http
            HttpHeaderNames
            DefaultHttpHeaders]))

(defprotocol Headers
  (^io.netty.handler.codec.http.DefaultHttpHeaders ->headers [_]))

(let [base-headers (doto (DefaultHttpHeaders. false)
                         (.add HttpHeaderNames/SERVER (str "iny/" version)))]
  (defn ^DefaultHttpHeaders headers-with-date
    []
    (doto (.copy ^DefaultHttpHeaders base-headers)
          (.add HttpHeaderNames/DATE (date-header-value))))

  (extend-protocol Headers
    nil
    (->headers [_]
      (headers-with-date))

    PersistentArrayMap
    (->headers [^Iterable header-map]
      (let [headers ^DefaultHttpHeaders (headers-with-date)
            i (.iterator header-map)]
        (loop []
          (if (.hasNext i)
            (let [elem ^Map$Entry (.next i)]
              (.set headers
                    (-> elem .getKey (name) .toString .toLowerCase)
                    (.getValue elem))
              (recur))))
        headers))))

(defn headers->map
  [headers]
  (persistent!
   (reduce-kv
    (fn [aggr ^String k v]
      (assoc! aggr (-> k (.toLowerCase)) v))
    (transient {})
    (into {} headers))))
