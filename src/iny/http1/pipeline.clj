(ns iny.http1.pipeline
  (:require [clojure.tools.logging :as log]
            [iny.netty.handler :as handler]
            [iny.http1.handler :refer [http-handler]]
            [iny.tls :refer [->ssl-context-builder]])
  (:import [io.netty.channel
            ChannelPipeline
            ChannelHandler
            ChannelHandler$Sharable]
           [io.netty.handler.codec.http
            HttpRequestDecoder
            HttpResponseEncoder
            HttpServerCodec
            HttpServerExpectContinueHandler]
           [io.netty.handler.ssl
            SslContextBuilder]))

(def ^{ChannelHandler$Sharable true :tag ChannelHandler} read-more
  (handler/inbound
   (channelRead [_ ctx msg]
    (.fireChannelRead ctx msg)
    (.read ctx))))

(defn server-pipeline
  [^ChannelPipeline pipeline {:keys [ssl worker-group] :as options}]
  (when (and ssl (not (.get pipeline "ssl-handler")))
    (let [context (.build ^SslContextBuilder (->ssl-context-builder ssl))]
      (.addBefore pipeline "ring-handler" "ssl-handler"
                  (.newHandler context (.alloc (.channel pipeline))))))

  ;; HACK: fix the problem of the channel getting "stuck" with chunked
  ;; requests. i have no idea why this is necessary... autoRead just
  ;; doesn't seem to function the way it does with the http/2 codecs
  (.addBefore pipeline "ring-handler" "read-more" read-more)

  (when-not (.get pipeline HttpServerCodec)
    (.addBefore pipeline "ring-handler" "http-inbound"
                (HttpRequestDecoder. 4096 8192 65536))

    (.addBefore pipeline worker-group "ring-handler" "http-outbound"
                (HttpResponseEncoder.)))

  (.addBefore pipeline "ring-handler" "continue"
              (HttpServerExpectContinueHandler.))

  (.addBefore pipeline "ring-handler" "iny-http1-inbound"
              (http-handler options)))
