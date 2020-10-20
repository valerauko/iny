(ns iny.http2.handler
  (:require [clojure.tools.logging :as log])
  (:import [java.nio.charset
            Charset]
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
            HttpHeaderNames]
           [io.netty.handler.codec.http
            HttpResponseStatus
            HttpServerUpgradeHandler$UpgradeEvent]
           [io.netty.handler.codec.http2
            Http2ConnectionHandler
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
  [request]
  (let [host (some-> request
                     (.headers)
                     (.get HttpHeaderNames/HOST))]
    (doto (DefaultHttp2Headers.)
          (.method (.asciiName HttpMethod/GET))
          (.path (.uri request))
          (.scheme (.name HttpScheme/HTTP))
          (.authority host))))

(defn respond
  [encoder ctx stream-id]
  (let [headers (doto (DefaultHttp2Headers.)
                      (.status (.codeAsText HttpResponseStatus/OK)))]
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
                             (.upgradeRequest event))]
       (.onHeadersRead this ctx 1 (translate-headers request) 0 true))
     (proxy-super userEventTriggered ctx event))
    (exceptionCaught
     [ctx ^Throwable error]
     (log/error error)
     (.close ctx))
    (onDataRead
     [ctx stream-id data padding end-of-stream?]
     (log/info "onDataRead")
     (when end-of-stream?
       (respond (.encoder this) ctx stream-id))
     (+ (.readableBytes data) padding))
    (onHeadersRead
     ; ([ctx stream-id headers padding end-of-stream?]
     ;  (if end-of-stream?
     ;    (respond (.encoder this) ctx stream-id)
     ;    (log/info "onHeadersRead 6")))
     ; ([ctx stream-id headers dependency weight exclusive? padding end-of-stream?]
     ;  (log/info "onHeadersRead 8"))
     ([ctx stream-id headers & others]
      (log/info "onHeadersRead")
      (when (last others)
        (respond (.encoder this) ctx stream-id))))
    (onPriorityRead
     [ctx stream-id dependency weight exclusive?]
     (log/info "onPriorityRead"))
    (onRstStreamRead
     [ctx stream-id error-code]
     (log/info "onRstStreamRead"))
    (onSettingsRead
     [ctx settings]
     (log/info (str settings)))
    (onSettingsAckRead
     [ctx]
     (log/info "onSettingsAckRead"))
    (onPingRead
     [ctx data]
     (log/info "onPingRead"))
    (onPingAckRead
     [ctx data]
     (log/info "onPingAckRead"))
    (onPushPromiseRead
     [ctx stream-id promised-id headers padding]
     (log/info "onPushPromiseRead"))
    (onGoAwayRead
     [ctx last-stream-id error-code debug-data]
     (log/info "onGoAwayRead"))
    (onWindowUpdateRead
     [ctx stream-id window-size-increment]
     (log/info "onWindowUpdateRead"))
    (onUnknownFrame
     [ctx frame-type stream-id flags payload]
     (log/info "onUnknownFrame"))))
