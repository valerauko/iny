(ns iny.runner
  (:require [clojure.tools.logging :as log]
            [potemkin :refer [def-derived-map]]
            [clj-async-profiler.core :as prof]
            [jsonista.core :as json])
  (:import [clojure.lang
            PersistentArrayMap]
           [java.util
            Date
            TimeZone
            Locale
            Collections
            Map$Entry]
           [java.util.concurrent
            TimeUnit]
           [java.util.concurrent.atomic
            AtomicReference]
           [java.text
            DateFormat
            SimpleDateFormat]
           [java.io
            IOException]
           [java.net
            InetSocketAddress]
           [java.nio
            ByteBuffer]
           [io.netty.util
            AsciiString
            ReferenceCountUtil]
           [io.netty.util.concurrent
            FastThreadLocal]
           [java.nio.charset
            Charset]
           [io.netty.bootstrap
            ServerBootstrap]
           [io.netty.buffer
            ByteBuf
            Unpooled
            PooledByteBufAllocator
            AdvancedLeakAwareByteBuf]
           [io.netty.channel
            MultithreadEventLoopGroup
            ChannelOption
            ChannelInitializer
            ChannelFuture
            ChannelFutureListener
            ChannelHandlerContext
            ChannelHandler
            ChannelInboundHandler]
           [io.netty.channel.nio
            NioEventLoopGroup]
           [io.netty.channel.epoll
            Epoll
            EpollEventLoopGroup
            EpollServerSocketChannel]
           [io.netty.channel.socket
            SocketChannel]
           [io.netty.channel.socket.nio
            NioServerSocketChannel]
           [io.netty.handler.flush
            FlushConsolidationHandler]
           [io.netty.handler.codec.http
            HttpUtil
            HttpServerCodec
            HttpServerExpectContinueHandler
            HttpContent
            LastHttpContent
            HttpRequest
            HttpResponse
            HttpRequestDecoder
            HttpResponseEncoder
            HttpVersion
            HttpResponseStatus
            HttpHeaders
            HttpHeaderNames
            DefaultHttpHeaders
            DefaultHttpContent
            DefaultHttpResponse])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn my-handler [{uri :uri}]
  {:status 200
   :body (json/write-value-as-bytes {:message (str "Hello from " uri)})
   :headers {"content-type" "application/json"}})

(def version "0.1.0")

(defonce ^FastThreadLocal date-format (FastThreadLocal.))
(defonce ^FastThreadLocal date-value (FastThreadLocal.))

(defmacro ref-get-or-set [^FastThreadLocal ftl or-value]
  `(if-let [^AtomicReference ref# (.get ~ftl)]
     (.get ref#)
     (let [new-ref# (AtomicReference. ~or-value)]
       (.set ~ftl new-ref#)
       (.get new-ref#))))

(defn ^DateFormat header-date-format
  "SimpleDateFormat isn't thread-safe so it has to be made for each worker"
  []
  (doto (SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss z" Locale/ENGLISH)
        (.setTimeZone (TimeZone/getTimeZone "GMT"))))

(defn formatted-header-date []
  (let [^DateFormat format (ref-get-or-set date-format (header-date-format))]
    (AsciiString. (.format format (Date.)))))

(defn ^CharSequence date-header-value []
  (ref-get-or-set date-value (formatted-header-date)))

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

(defprotocol ResponseStatus
  (^HttpResponseStatus ->status [_]))

(extend-protocol ResponseStatus
  nil
  (->status [_] HttpResponseStatus/OK)

  HttpResponseStatus
  (->status [status] status)

  Integer
  Long
  (->status [number] (HttpResponseStatus/valueOf number)))

(defprotocol Headers
  (^HttpHeaders ->headers [_]))

(defn ^ChannelFuture write-response
  [^ChannelHandlerContext ctx
   ^HttpResponse          head
   ^HttpContent           body]
  (.write ctx head (.voidPromise ctx))
  (if body (.write ctx body (.voidPromise ctx)))
  (.writeAndFlush ctx LastHttpContent/EMPTY_LAST_CONTENT))

(let [base-headers (doto (DefaultHttpHeaders. false)
                         (.add HttpHeaderNames/SERVER (str "iny/" version))
                         (.add HttpHeaderNames/CONTENT_TYPE "text/plain"))]
  (defn headers-with-date
    []
    (doto (.copy base-headers)
          (.add HttpHeaderNames/DATE (date-header-value))))

  (extend-protocol Headers
    nil
    (->headers [_]
      (headers-with-date))

    PersistentArrayMap
    (->headers [^Iterable header-map]
      (let [headers ^HttpHeaders (headers-with-date)
            i (.iterator header-map)]
        (loop []
          (if (.hasNext i)
            (let [elem ^Map$Entry (.next i)]
              (.set headers
                    (-> elem .getKey .toString .toLowerCase)
                    (.getValue elem))
              (recur))))
        headers)))

  (let [error-head (doto (DefaultHttpResponse.
                          HttpVersion/HTTP_1_1
                          HttpResponseStatus/INTERNAL_SERVER_ERROR
                          ^HttpHeaders (headers-with-date))
                         (HttpUtil/setContentLength 0))]
    (defn ^ChannelFuture respond-500
      [^ChannelHandlerContext ctx
       ^Throwable             ex]
      (write-response ctx error-head nil)))

  (defn ^ChannelFuture respond
    [^ChannelHandlerContext ctx
     {:keys [body headers status]}]
    (let [status (->status status)
          buffer (->buffer body ctx)
          headers (->headers headers)
          response (DefaultHttpResponse.
                    HttpVersion/HTTP_1_1
                    status
                    headers)
          response-body (DefaultHttpContent. buffer)]
      (HttpUtil/setContentLength response (.readableBytes buffer))
      (write-response ctx response response-body))))

(let [methods (-> {"OPTIONS" :options
                   "GET" :get
                   "HEAD" :head
                   "POST" :post
                   "PUT" :put
                   "PATCH" :patch
                   "DELETE" :delete
                   "TRACE" :trace
                   "CONNECT" :connect}
                  (java.util.HashMap.)
                  (Collections/unmodifiableMap))]
  (defn request-method
    [^HttpRequest req]
    (->> req (.method) (.name) (.get methods))))

(def-derived-map RingRequest
  [^ChannelHandlerContext ctx
   ^HttpRequest           req
   q-at]
  :uri            (if (not (neg? q-at))
                    (.substring (.uri req) 0 q-at)
                    (.uri req))
  :query-string   (if (not (neg? q-at))
                    (.substring (.uri req) q-at))
  :headers        (.headers req)
  :request-method (request-method req)
  :scheme         :http
  :server-name    (some-> ctx (.channel) ^InetSocketAddress (.localAddress) (.getHostName))
  :server-port    (some-> ctx (.channel) ^InetSocketAddress (.localAddress) (.getPort))
  :remote-addr    (some-> ctx (.channel) ^InetSocketAddress (.remoteAddress) (.getAddress) (.getHostAddress))
  :iny/keep-alive (HttpUtil/isKeepAlive req))

(defn netty->ring-request
  [^ChannelHandlerContext ctx
   ^HttpRequest           req]
  (->RingRequest ctx req (.indexOf (.uri req) (int 63))))

(defn ^ChannelInboundHandler http-handler
  [user-handler]
  (let [keep-alive? (atom false)]
    (reify
      ChannelInboundHandler

      (handlerAdded [_ ctx])
      (handlerRemoved [_ ctx])
      (exceptionCaught [_ ctx ex]
        (when-not (instance? IOException ex)
          (respond-500 ctx ex)))
      (channelRegistered [_ ctx]
        (let [ref (AtomicReference. (formatted-header-date))]
          ; header date is accurate to seconds
          ; so have it update every second
          (.set date-value ref)
          (.scheduleAtFixedRate (.executor ctx)
            #(.set ref (formatted-header-date))
            1000
            1000
            TimeUnit/MILLISECONDS)))
      (channelUnregistered [_ ctx])
      (channelActive [_ ctx])
      (channelInactive [_ ctx])
      (channelRead [_ ctx msg]
        (cond
          (instance? HttpRequest msg)
            (when-not (HttpHeaders/isTransferEncodingChunked msg)
              (let [ftr (->> msg
                             (netty->ring-request ctx)
                             (user-handler)
                             (respond ctx))]
                (when-not keep-alive?
                  (.addListener ftr ChannelFutureListener/CLOSE))))
          ; (instance? LastHttpContent msg)
          ;   nil
          (instance? HttpContent msg)
            nil
          :else
            (.fireChannelRead ctx msg)
         ))
      (channelReadComplete [_ ctx])
      (userEventTriggered [_ ctx event])
      (channelWritabilityChanged [_ ctx]))))

(defn server-pipeline
  [user-handler]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel ch]
      (let [pipeline (.pipeline ch)]
        (.addLast pipeline "optimize-flushes" (FlushConsolidationHandler.))
        (.addLast pipeline "http-decoder" (HttpRequestDecoder.))
        (.addLast pipeline "http-encoder" (HttpResponseEncoder.))
        (.addLast pipeline "continue" (HttpServerExpectContinueHandler.))
        (.addLast pipeline "my-handler" (http-handler user-handler))))))

(let [cores (.availableProcessors (Runtime/getRuntime))
      epoll? (Epoll/isAvailable)
      socket-chan (if epoll? EpollServerSocketChannel NioServerSocketChannel)]
  (defn -main
    [& _]
    (let [parent-group (if epoll?
                         (EpollEventLoopGroup. (* 2 cores))
                         (NioEventLoopGroup. (* 2 cores)))
          child-group (if epoll?
                        (EpollEventLoopGroup. (* 3 cores))
                        (NioEventLoopGroup. (* 3 cores)))
          port 8080]
      (try
        (let [boot (doto (ServerBootstrap.)
                         (.option ChannelOption/SO_BACKLOG (int 1024))
                         (.option ChannelOption/SO_REUSEADDR true)
                         (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                         (.option ChannelOption/ALLOCATOR (PooledByteBufAllocator. true))
                         (.group ^MultithreadEventLoopGroup parent-group
                                 ^MultithreadEventLoopGroup child-group)
                         (.channel socket-chan)
                         (.childHandler (server-pipeline my-handler))
                         (.childOption ChannelOption/SO_REUSEADDR true)
                         (.childOption ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                         (.childOption ChannelOption/ALLOCATOR (PooledByteBufAllocator. true)))
              channel (-> boot (.bind port) .sync .channel)]
          (fn closer []
            (-> channel .close .sync)
            (.shutdownGracefully ^MultithreadEventLoopGroup parent-group)
            (.shutdownGracefully ^MultithreadEventLoopGroup child-group)))
        (catch Exception e
          @(.shutdownGracefully ^MultithreadEventLoopGroup parent-group)
          @(.shutdownGracefully ^MultithreadEventLoopGroup child-group))))))
