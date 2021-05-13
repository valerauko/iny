(ns iny.ring.request
  (:require [potemkin :refer [def-derived-map]])
  (:import [java.io
            PipedInputStream]
           [java.net
            InetSocketAddress]
           [io.netty.channel
            ChannelHandlerContext]))

(def-derived-map RingRequest
  [^ChannelHandlerContext ctx
   ^String uri
   headers-fn
   method-fn
   scheme
   ^PipedInputStream body
   q-at]
  :uri            (if (not (neg? ^int q-at))
                    (.substring uri 0 q-at)
                    uri)
  :query-string   (if (not (neg? ^int q-at))
                    (.substring uri q-at))
  :headers        (headers-fn)
  :request-method (method-fn)
  :scheme         (scheme)
  :body           body
  :server-name    (some-> ctx (.channel) ^InetSocketAddress (.localAddress) (.getHostName))
  :server-port    (some-> ctx (.channel) ^InetSocketAddress (.localAddress) (.getPort))
  :remote-addr    (some-> ctx (.channel) ^InetSocketAddress (.remoteAddress) (.getAddress) (.getHostAddress)))
