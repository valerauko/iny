(ns iny.http2.handler
  (:require [clojure.tools.logging :as log]
            [potemkin :refer [def-derived-map]]
            [iny.http.date :refer [schedule-date-value-update]]
            [iny.http.method :refer [http-methods]]
            [iny.http.status :refer [->status]]
            [iny.http.body :refer [->buffer release]]
            [iny.http2.headers :refer [->headers]])
  (:import [java.net
            InetSocketAddress]
           [io.netty.buffer
            ByteBuf
            ByteBufInputStream]
           [io.netty.channel
            ChannelFuture
            ChannelFutureListener
            ChannelHandlerContext
            ChannelInboundHandler]
           [io.netty.handler.codec.http
            HttpHeaderNames
            HttpUtil
            HttpResponseStatus]
           [io.netty.handler.codec.http2
            Http2FrameCodec
            Http2FrameStream
            ;; frames
            Http2DataFrame
            Http2HeadersFrame
            DefaultHttp2DataFrame
            DefaultHttp2HeadersFrame
            ;;
            Http2Headers
            Http2Headers$PseudoHeaderName
            DefaultHttp2Headers]))

(defn request-method
  [^Http2Headers headers]
  (->> headers (.method) (str) (.get http-methods)))

(def-derived-map RingRequest
  [^ChannelHandlerContext ctx
   ^Http2HeadersFrame     req
   ^Http2Headers          headers
   ^ByteBuf               body
   ^String                path
   q-at]
  :uri            (if (not (neg? ^int q-at))
                    (.substring path 0 q-at)
                    path)
  :query-string   (if (not (neg? ^int q-at))
                    (.substring path q-at))
  :headers        headers
  :request-method (str (request-method headers))
  :scheme         (-> headers (.scheme) (str) (keyword))
  :body           (ByteBufInputStream. body false)
  :server-name    (some-> ctx (.channel) ^InetSocketAddress (.localAddress) (.getHostName))
  :server-port    (some-> ctx (.channel) ^InetSocketAddress (.localAddress) (.getPort))
  :remote-addr    (some-> ctx (.channel) ^InetSocketAddress (.remoteAddress) (.getAddress) (.getHostAddress)))

(defn netty->ring-request
  [^ChannelHandlerContext ctx
   ^ByteBuf               body
   ^Http2HeadersFrame     req]
  (let [headers (.headers req)
        path (.toString (.path headers))]
    (->RingRequest ctx req headers body path
                   (.indexOf path (int 63)))))

(let [empty-last-data (DefaultHttp2DataFrame. true)]
  (defn respond
    [^ChannelHandlerContext ctx
     ^Http2FrameStream stream
     {:keys [body headers status]}]
    (let [status (->status status)
          buffer (when body (->buffer body ctx))
          headers (doto (->headers headers)
                        (.set (.value Http2Headers$PseudoHeaderName/STATUS)
                              (.codeAsText status))
                        (.set HttpHeaderNames/CONTENT_LENGTH
                              (if buffer
                                (String/valueOf (.readableBytes buffer))
                                "0")))
          headers-frame (doto (DefaultHttp2HeadersFrame. headers)
                              (.stream stream))]
      (.write ctx headers-frame (.voidPromise ctx))
      ;; can't write with VoidPromise, because then writeAndFlush
      ;; starts generating massive amounts of IllegalStateExceptions.
      ;; cause: Http2ConnectionHandler#closeStream tries to add
      ;; a listener to the future, which fails automatically if the
      ;; future is a VoidPromise.
      (when body
        (let [data-frame (doto (DefaultHttp2DataFrame. buffer false)
                               (.stream stream))]
          (.write ctx data-frame)))
      (let [last-frame (doto (.copy empty-last-data)
                             (.stream stream))]
        (.writeAndFlush ctx last-frame)))))

(defn content-length'
  [^Http2HeadersFrame req]
  (when-let [header-value (-> req
                              (.headers)
                              (.get "Content-Length"))]
    (try
      (Long/parseLong header-value)
      (catch Throwable e
        (log/debug "Wrong content length header value" e)
        nil))))

(def content-length (memoize content-length'))

(defn http2-handler
  [user-handler frame-codec]
  (let [requests (atom {})]
    (reify
      ChannelInboundHandler

      (handlerAdded
       [_ ctx]
       (let [pipeline (.pipeline ctx)]
         ;; add bidi codec handler to the pipeline if not present yet
         (when-not (.get pipeline Http2FrameCodec)
           (.addBefore pipeline (.name ctx) nil frame-codec))))
      (handlerRemoved [_ ctx])
      (exceptionCaught
       [_ ctx ex]
       (log/warn ex)
       (.close ctx))
      (channelRegistered
       [_ ctx]
       (schedule-date-value-update ctx))
      (channelUnregistered [_ ctx])
      (channelActive [_ ctx])
      (channelInactive [_ ctx])
      (channelRead
       [_ ctx msg]
       (cond
         (instance? Http2HeadersFrame msg)
         (let [stream (.stream ^Http2HeadersFrame msg)]
           (cond
             ;; request without body
             (.isEndStream ^Http2HeadersFrame msg)
             (->> msg
                  (netty->ring-request ctx (->buffer nil))
                  ^IPersistentMap (user-handler)
                  (respond ctx stream))
             ;; request with body
             ;; netty's HttpPostRequestDecoder can't handle http/2 frames
             :else
             (let [allocator (.alloc ctx)
                   buffer (if-let [len (content-length msg)]
                            (.buffer allocator len)
                            (.buffer allocator))]
               (swap! requests assoc stream
                      [(netty->ring-request ctx buffer msg) buffer]))))
         ;; body frames
         (instance? Http2DataFrame msg)
         (let [stream (.stream ^Http2DataFrame msg)
               [request ^ByteBuf buffer] (get @requests stream)]
           (.writeBytes buffer (.content ^Http2DataFrame msg))
           (when (.isEndStream ^Http2DataFrame msg)
             (->> request
                  ^IPersistentMap (user-handler)
                  (respond ctx stream))
             (release buffer)
             (swap! requests assoc stream nil))))
       (release msg))
      (channelReadComplete [_ ctx])
      (userEventTriggered [_ ctx event])
      (channelWritabilityChanged [_ ctx]))))
