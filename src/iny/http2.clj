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
  [build-http-pipeline executor]
  (reify
    ChannelInboundHandler
    (handlerAdded [_ _])
    (handlerRemoved [_ _])
    (exceptionCaught
     [_ ctx ex]
     (.fireExceptionCaught ctx ex))
    (channelRegistered [_ ctx])
    (channelUnregistered [_ ctx])
    (channelActive [_ ctx])
    (channelInactive [_ ctx])
    (channelRead
     [this ctx msg]
     (let [pipeline (.pipeline ctx)]
       ;; removes the h2c-upgrade handler (no upgrade was attempted)
       (build-http-pipeline pipeline executor)
       (.fireChannelRead ctx msg)
       (.remove pipeline this)))
    (channelReadComplete [_ ctx])
    (userEventTriggered [_ ctx event])
    (channelWritabilityChanged [_ ctx])))

(let [builder (Http2FrameCodecBuilder/forServer)]
  (defn ^Http2FrameCodec codec
    []
    (.build builder)))

(defn upgrade-factory
  [http2-handler]
  (reify
    HttpServerUpgradeHandler$UpgradeCodecFactory
    (newUpgradeCodec [_ proto]
      (condp #(AsciiString/contentEquals %1 %2) proto
        Http2CodecUtil/HTTP_UPGRADE_PROTOCOL_NAME
          (Http2ServerUpgradeCodec.
           (codec)
           ^"[Lio.netty.channel.ChannelHandler;"
           (into-array ChannelHandler [http2-handler]))))))

(defn ^CleartextHttp2ServerUpgradeHandler h2c-upgrade
  [executor]
  (let [http-codec (HttpServerCodec.)
        handler (http2-handler (codec) executor)
        factory (upgrade-factory handler)]
    (CleartextHttp2ServerUpgradeHandler.
     http-codec
     (HttpServerUpgradeHandler. http-codec factory)
     handler)))

(defn server-pipeline
  [^ChannelPipeline pipeline executor]
  (.addBefore pipeline "ring-handler" "h2c-upgrade" (h2c-upgrade executor))
  (.addBefore pipeline "ring-handler" "http-fallback"
              (http-fallback http1/server-pipeline executor)))
