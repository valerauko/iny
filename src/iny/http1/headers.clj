(ns iny.http1.headers
  (:require [iny.meta :refer [version]]
            [iny.http.date :refer [date-header-value]]
            [iny.http3.headers :as http3])
  (:import [java.util
            Map$Entry]
           [clojure.lang
            IPersistentMap]
           [io.netty.handler.codec.http
            HttpHeaderNames
            DefaultHttpHeaders]))

(defprotocol Headers
  (^io.netty.handler.codec.http.DefaultHttpHeaders ->headers [_ _]))

(let [base-headers (doto (DefaultHttpHeaders. false)
                         (.add HttpHeaderNames/SERVER (str "iny/" version)))]
  (defn ^DefaultHttpHeaders headers-with-date
    [{:keys [http3] :as opts}]
    (let [copied (.copy ^DefaultHttpHeaders base-headers)]
      (when http3 (.add copied http3/alt-svc-name http3/alt-svc-value))
      (.add copied HttpHeaderNames/DATE (date-header-value opts))
      copied))

  (extend-protocol Headers
    nil
    (->headers [_ opts]
      (headers-with-date opts))

    IPersistentMap
    (->headers [header-map opts]
      (let [headers ^DefaultHttpHeaders (headers-with-date opts)
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
