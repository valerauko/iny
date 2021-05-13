(ns iny.http3.headers
  (:require [clojure.string :refer [join]])
  (:import [io.netty.incubator.codec.http3
            Http3]
           [io.netty.util
            AsciiString]))

(def alt-svc-name
  (AsciiString. "alt-svc"))

(def alt-svc-value
  (->> (Http3/supportedApplicationProtocols)
       (seq)
       (map #(format "%s=\":8080\"; ma=2592000" %))
       (join ",")))
