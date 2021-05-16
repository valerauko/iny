(ns iny.http1.pipeline
  (:require [iny.http1.handler :refer [http-handler]]
            [iny.tls :refer [->ssl-context-builder]])
  (:import [io.netty.channel
            ChannelPipeline]
           [io.netty.handler.codec.http
            HttpRequestDecoder
            HttpResponseEncoder
            HttpServerCodec
            HttpServerExpectContinueHandler]
           [io.netty.handler.ssl
            SslContextBuilder]))

(defn server-pipeline
  ([pipeline] (server-pipeline pipeline {}))
  ([^ChannelPipeline pipeline {:keys [ssl]}]
   (when ssl
     (let [context (.build ^SslContextBuilder (->ssl-context-builder ssl))]
       (.addBefore pipeline "ring-handler" "ssl-handler"
                   (.newHandler context (.alloc (.channel pipeline))))))
   (.addBefore pipeline "ring-handler" "continue" (HttpServerExpectContinueHandler.))
   (.addBefore pipeline "ring-handler" "iny-http1-inbound" (http-handler))))
