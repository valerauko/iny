(ns iny.http1.pipeline
  (:require [iny.netty.handler :as handler]
            [iny.http1.handler :refer [http-handler]]
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

   ;; HACK: fix the problem of the channel getting "stuck" with chunked
   ;; requests. i have no idea why this is necessary... autoRead just
   ;; doesn't seem to function the way it does with the http/2 codecs
   (.addBefore pipeline "ring-handler" "read-more"
               (handler/inbound
                (channelRead [_ ctx msg]
                 (.fireChannelRead ctx msg)
                 (.read ctx))))

   (when-not (.get pipeline HttpServerCodec)
     (.addBefore pipeline "ring-handler" "http-inbound"
                 (HttpRequestDecoder. 4096 8192 65536))

     (let [ring-executor (.executor (.context pipeline "ring-handler"))]
       (.addBefore pipeline ring-executor "ring-handler" "http-outbound"
                   (HttpResponseEncoder.))))

   (.addBefore pipeline "ring-handler" "continue"
               (HttpServerExpectContinueHandler.))

   (.addBefore pipeline "ring-handler" "iny-http1-inbound"
               (http-handler))))
