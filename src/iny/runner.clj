(ns iny.runner
  (:require [clojure.tools.logging :as log]
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

(defn rfc-1123-date-string []
  (let [^DateFormat format
        (or
          (.get date-format)
          (let [format (SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss z" Locale/ENGLISH)]
            (.setTimeZone format (TimeZone/getTimeZone "GMT"))
            (.set date-format format)
            format))]
    (AsciiString. (.format format (Date.)))))

(defn ^CharSequence date-header-value []
  (if-let [^AtomicReference ref (.get date-value)]
    (.get ref)
    (let [ref (AtomicReference. (rfc-1123-date-string))]
      (.set date-value ref)
      (.get ref))))

(defprotocol WritableBody
  (^ByteBuf ->buffer [_]))

(let [charset (Charset/forName "UTF-8")]
  (extend-protocol WritableBody
    (Class/forName "[B")
    (->buffer [b]
      (Unpooled/copiedBuffer ^bytes b))

    nil
    (->buffer [_]
      Unpooled/EMPTY_BUFFER)

    String
    (->buffer [str]
      (Unpooled/copiedBuffer ^String str charset))))

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

  (defn ^ChannelFuture write-response
    [^ChannelHandlerContext ctx
     ^HttpResponse          head
     ^HttpContent           body]
    (.write ctx head (.voidPromise ctx))
    (if body (.write ctx body (.voidPromise ctx)))
    (.writeAndFlush ctx LastHttpContent/EMPTY_LAST_CONTENT))

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
          buffer (->buffer body)
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
  (defn netty->ring-request
    [^ChannelHandlerContext ctx
     ^HttpRequest           req]
    (let [uri     (.uri req)
          q-at    (.indexOf uri (int 63))
          query?  (not (neg? q-at))
          channel (.channel ctx)]
      {:uri            (if query?
                         (.substring uri 0 q-at)
                         uri)
       :query-string   (if query?
                         (.substring uri q-at))
       :headers        (.headers req)
       :request-method (->> req .method .name (.get methods))
       :scheme         :http
       :server-name    (some-> channel ^InetSocketAddress (.localAddress) .getHostName)
       :server-port    (some-> channel ^InetSocketAddress (.localAddress) .getPort)
       :remote-addr    (some-> channel ^InetSocketAddress (.remoteAddress) .getAddress .getHostAddress)
       :iny/keep-alive (HttpUtil/isKeepAlive req)})))

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
        (let [ref (AtomicReference. (rfc-1123-date-string))]
          (.set date-value ref)
          (.scheduleAtFixedRate (.executor ctx)
            #(.set ref (rfc-1123-date-string))
            1000
            1000
            TimeUnit/MILLISECONDS)))
      (channelUnregistered [_ ctx])
      (channelActive [_ ctx])
      (channelInactive [_ ctx])
      (channelRead [_ ctx msg]
        (cond
          (instance? HttpRequest msg)
            (let []
              (reset! keep-alive? (HttpUtil/isKeepAlive msg)))
          (instance? LastHttpContent msg)
            (let [ftr (respond ctx (user-handler))]
              (when-not keep-alive?
                (.addListener ftr ChannelFutureListener/CLOSE)))
          ; (instance? HttpContent msg)
          ;   (println "fuga")
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
        (.addLast pipeline "http-codec" (HttpServerCodec.))
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
                         (.group ^MultithreadEventLoopGroup parent-group
                                 ^MultithreadEventLoopGroup child-group)
                         (.channel socket-chan)
                         (.childHandler (server-pipeline my-handler)))
              channel (-> boot (.bind port) .sync .channel)]
          (fn closer []
            (-> channel .close .sync)
            (.shutdownGracefully ^MultithreadEventLoopGroup parent-group)
            (.shutdownGracefully ^MultithreadEventLoopGroup child-group)))
        (catch Exception e
          @(.shutdownGracefully ^MultithreadEventLoopGroup parent-group)
          @(.shutdownGracefully ^MultithreadEventLoopGroup child-group))))))
