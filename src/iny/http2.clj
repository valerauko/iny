(ns iny.http2
  (:require [clojure.tools.logging :as log]
            [iny.http1 :as http1]
            [iny.http2.handler :refer [http2-handler]])
  (:import [io.netty.util
            AsciiString
            ReferenceCountUtil]
           [io.netty.channel
            ChannelHandler
            ChannelInboundHandler
            ChannelInitializer
            ChannelPipeline]
           [io.netty.channel.socket
            SocketChannel]
           [io.netty.handler.codec.http
            HttpServerCodec
            HttpServerUpgradeHandler
            HttpServerUpgradeHandler$UpgradeCodecFactory]
           [io.netty.handler.codec.http2
            Http2FrameCodecBuilder
            Http2CodecUtil
            CleartextHttp2ServerUpgradeHandler
            Http2ServerUpgradeCodec
            Http2FrameCodec]))

(defn ^ChannelHandler http-fallback
  [build-http-pipeline user-handler]
  (reify
    ChannelInboundHandler
    (handlerAdded [_ _])
    (handlerRemoved [_ _])
    (exceptionCaught
     [_ ctx ex]
     (log/error ex))
    (channelRegistered [_ ctx])
    (channelUnregistered [_ ctx])
    (channelActive [_ ctx])
    (channelInactive [_ ctx])
    (channelRead
     [this ctx msg]
     (let [pipeline (.pipeline ctx)]
       ;; removes the h2c-upgrade handler (no upgrade was attempted)
       (.remove pipeline HttpServerCodec)
       (.remove pipeline HttpServerUpgradeHandler)
       (build-http-pipeline pipeline user-handler)
       (.remove pipeline this)
       (.fireChannelRead ctx (ReferenceCountUtil/retain msg))))
    (channelReadComplete [_ ctx])
    (userEventTriggered [_ ctx event])
    (channelWritabilityChanged [_ ctx])))

(defn ^Http2FrameCodec codec
  []
  (.build (Http2FrameCodecBuilder/forServer)))

(defn upgrade-factory
  [handler]
  (reify
    HttpServerUpgradeHandler$UpgradeCodecFactory
    (newUpgradeCodec [_ proto]
      (when (AsciiString/contentEquals
             Http2CodecUtil/HTTP_UPGRADE_PROTOCOL_NAME
             proto)
        (Http2ServerUpgradeCodec.
         (codec)
         ^"[Lio.netty.channel.ChannelHandler;"
         (into-array ChannelHandler [handler]))))))

(defn ^CleartextHttp2ServerUpgradeHandler h2c-upgrade
  [user-handler]
  (let [http-codec (HttpServerCodec.)
        handler (http2-handler user-handler (codec))
        factory (upgrade-factory handler)]
    (CleartextHttp2ServerUpgradeHandler.
     http-codec
     (HttpServerUpgradeHandler. http-codec factory)
     handler)))

(defn server-pipeline
  [^ChannelPipeline pipeline user-handler]
  (.addLast
   pipeline
   "h2c-upgrade"
   (h2c-upgrade user-handler))
  (.addLast
   pipeline
   "http-fallback"
   (http-fallback http1/server-pipeline user-handler)))
