(ns iny.http.handler
  (:require [clojure.tools.logging :as log]
            [iny.meta :refer [version]]
            [iny.http.date :refer [schedule-date-value-update
                                   date-header-value]]
            [iny.http.conversion :refer [->buffer ->status]]
            [potemkin :refer [def-derived-map]])
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
            ResourceLeakDetector
            ResourceLeakDetector$Level]
           [io.netty.util.concurrent
            FastThreadLocal]
           [java.nio.charset
            Charset]
           [io.netty.buffer
            ByteBuf
            Unpooled
            AdvancedLeakAwareByteBuf]
           [io.netty.channel
            ChannelFuture
            ChannelPipeline
            ChannelFutureListener
            ChannelHandlerContext
            ChannelHandler
            ChannelInboundHandler]
           [io.netty.handler.flush
            FlushConsolidationHandler]
           [io.netty.handler.codec.http
            HttpUtil
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
            DefaultHttpResponse]))

(ResourceLeakDetector/setLevel ResourceLeakDetector$Level/DISABLED)

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
    (doto (.copy ^DefaultHttpHeaders base-headers)
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

  (defn ^ChannelFuture respond-500
    [^ChannelHandlerContext ctx
     ^Throwable             ex]
    (let [error-head (doto (DefaultHttpResponse.
                            HttpVersion/HTTP_1_1
                            HttpResponseStatus/INTERNAL_SERVER_ERROR
                            ^HttpHeaders (headers-with-date))
                           (HttpUtil/setContentLength 0))]
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
  :uri            (if (not (neg? ^int q-at))
                    (.substring (.uri req) 0 q-at)
                    (.uri req))
  :query-string   (if (not (neg? ^int q-at))
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
        (schedule-date-value-update ctx))
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
          ; (instance? HttpContent msg)
          ;   nil
          ; :else
          ;   (log/info (class msg))
          ))
      (channelReadComplete [_ ctx])
      (userEventTriggered [_ ctx event])
      (channelWritabilityChanged [_ ctx]))))
