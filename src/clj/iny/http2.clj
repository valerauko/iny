(ns iny.http2
  (:require [clojure.tools.logging :as log]
            [iny.http :refer [->buffer]])
  (:import [io.netty.util
            AsciiString]
           [io.netty.channel
            ChannelFuture
            ChannelFutureListener
            ChannelHandlerContext
            ChannelHandler
            ChannelInboundHandler]
           [io.netty.handler.codec.http
            HttpServerCodec
            HttpServerUpgradeHandler
            HttpServerUpgradeHandler$UpgradeCodecFactory
            HttpResponseStatus
            HttpRequest]
           [io.netty.handler.codec.http2
            Http2FrameCodecBuilder
            Http2CodecUtil
            DefaultHttp2Connection
            CleartextHttp2ServerUpgradeHandler
            Http2ServerUpgradeCodec
            AbstractHttp2ConnectionHandlerBuilder
            Http2ConnectionHandlerBuilder
            Http2ConnectionHandler
            Http2FrameListener
            Http2FrameCodec
            Http2HeadersFrame
            Http2DataFrame
            DefaultHttp2Headers
            DefaultHttp2HeadersFrame
            DefaultHttp2DataFrame]))

(defn ^Http2FrameCodec codec
  []
  (.build (Http2FrameCodecBuilder/forServer)))

(defn http2-handler
  [user-handler]
  (reify
    ChannelInboundHandler

    (handlerAdded
      [_ ctx]
      (let [pipeline (.pipeline ctx)]
        (when-not (.get pipeline Http2FrameCodec)
          (.addBefore pipeline (.name ctx) nil (codec)))))
    (handlerRemoved [_ ctx])
    (exceptionCaught
      [_ ctx ex]
      (log/warn ex)
      (.close ctx))
    (channelRegistered [_ ctx])
    (channelUnregistered [_ ctx])
    (channelActive [_ ctx])
    (channelInactive [_ ctx])
    (channelRead [_ ctx msg]
      (cond
        (instance? Http2HeadersFrame msg)
          (let [stream (.stream ^Http2HeadersFrame msg)]
            (.write ctx
                    (doto (DefaultHttp2HeadersFrame.
                           (doto (DefaultHttp2Headers.)
                                 (.status (.codeAsText ^HttpResponseStatus
                                                       HttpResponseStatus/OK))))
                          (.stream stream))
                    (.voidPromise ctx))
            (.writeAndFlush ctx
                            (doto (DefaultHttp2DataFrame.
                                   (->buffer "hello, world")
                                   true)
                                  (.stream stream))
                            (.voidPromise ctx)))
        ; (instance? Http2DataFrame msg)
        ;   ,,,
        :else
          (log/info (class msg))))
    (channelReadComplete [_ ctx])
    (userEventTriggered [_ ctx event])
    (channelWritabilityChanged [_ ctx])))

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

(defn h2c-upgrade
  [user-handler]
  (let [codec (HttpServerCodec.)
        handler (http2-handler user-handler)
        factory (upgrade-factory handler)]
    (CleartextHttp2ServerUpgradeHandler.
     codec
     (HttpServerUpgradeHandler. codec factory)
     handler)))
