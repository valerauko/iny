(ns iny.http2.handler
  (:require [clojure.tools.logging :as log])
  (:import [java.nio.charset
            Charset]
           [java.io
            IOException]
           [io.netty.buffer
            ByteBuf
            Unpooled
            PooledByteBufAllocator
            AdvancedLeakAwareByteBuf]
           [io.netty.channel
            ChannelHandlerContext]
           [io.netty.handler.codec.http
            HttpMethod
            HttpScheme
            HttpHeaderNames
            FullHttpRequest]
           [io.netty.handler.codec.http
            HttpResponseStatus
            HttpServerUpgradeHandler$UpgradeEvent]
           [io.netty.handler.codec.http2
            Http2ConnectionHandler
            Http2ConnectionEncoder
            Http2FrameListener
            Http2Headers
            DefaultHttp2Headers]))

(defprotocol WritableBody
  (^ByteBuf ->buffer [_] [_ _]))

(let [charset (Charset/forName "UTF-8")]
  (extend-protocol WritableBody
    (Class/forName "[B")
    (->buffer
      ([b]
        (Unpooled/copiedBuffer ^bytes b))
      ([b ctx]
        (doto (-> ^ChannelHandlerContext ctx
                  (.alloc)
                  (.ioBuffer (alength ^bytes b)))
              (.writeBytes ^bytes b))))

    nil
    (->buffer
      ([_]
        Unpooled/EMPTY_BUFFER)
      ([_ _]
        Unpooled/EMPTY_BUFFER))

    String
    (->buffer
      ([str]
        (Unpooled/copiedBuffer ^String str charset))
      ([str ctx]
        (->buffer ^bytes (.getBytes str) ctx)))))

(defn ^Http2Headers translate-headers
  [^FullHttpRequest request]
  (let [host (some-> request
                     (.headers)
                     (.get HttpHeaderNames/HOST))]
    (doto (DefaultHttp2Headers.)
          (.method (.asciiName HttpMethod/GET))
          (.path (.uri request))
          (.scheme (.name HttpScheme/HTTP))
          (.authority host))))

(defn respond
  [^Http2ConnectionEncoder encoder ^ChannelHandlerContext ctx stream-id]
  (let [headers (doto (DefaultHttp2Headers.)
                      (.status (.codeAsText ^HttpResponseStatus
                                            HttpResponseStatus/OK)))]
    (.writeHeaders encoder ctx stream-id headers 0 false (.newPromise ctx))
    (try
      (.writeData encoder ctx stream-id (->buffer "hello, world") 0 true (.newPromise ctx))
      (catch Throwable e
        (log/error e)
        (throw e)))))

(defn ^Http2ConnectionHandler http2-handler
  [decoder encoder initial-settings]
  (proxy
    [Http2ConnectionHandler Http2FrameListener]
    [decoder encoder initial-settings]
    (userEventTriggered
     [ctx event]
     ;; if an http1->http2 upgrade happened, pass the request
     ;; on to onHeadersRead for actual processing
     (when-let [request (and (instance? ;; only HSUH/UpgradeEvents have request
                              HttpServerUpgradeHandler$UpgradeEvent
                              event)
                             (.upgradeRequest
                              ^HttpServerUpgradeHandler$UpgradeEvent
                              event))]
       (.onHeadersRead ^Http2FrameListener this ctx 1 (translate-headers request) 0 true))
     (proxy-super userEventTriggered ctx event))
    (exceptionCaught
     [^ChannelHandlerContext ctx ^Throwable error]
     (when-not (instance? IOException error)
       (log/error error))
     (.close ctx))
    (onDataRead
     [ctx stream-id ^ByteBuf data padding end-of-stream?]
     (when end-of-stream?
       (respond (.encoder ^Http2ConnectionHandler this) ctx stream-id))
     (+ (.readableBytes data) ^long padding))
    (onHeadersRead
     ([ctx stream-id headers padding end-of-stream?]
      (when end-of-stream?
        (respond (.encoder ^Http2ConnectionHandler this) ctx stream-id)))
     ([ctx stream-id headers dependency weight exclusive? padding end-of-stream?]
      (when end-of-stream?
        (respond (.encoder ^Http2ConnectionHandler this) ctx stream-id))))
    (onPriorityRead [ctx stream-id dependency weight exclusive?])
    (onRstStreamRead
     [ctx stream-id error-code]
     (log/info "onRstStreamRead"))
    (onSettingsRead [ctx settings])
    (onSettingsAckRead [ctx])
    (onPingRead [ctx data])
    (onPingAckRead [ctx data])
    (onPushPromiseRead
     [ctx stream-id promised-id headers padding]
     (log/info "onPushPromiseRead"))
    (onGoAwayRead
     [ctx last-stream-id error-code debug-data]
     (log/info "onGoAwayRead"))
    (onWindowUpdateRead [ctx stream-id window-size-increment])
    (onUnknownFrame [ctx frame-type stream-id flags payload])))
