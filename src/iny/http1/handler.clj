(ns iny.http1.handler
  (:require [clojure.tools.logging :as log]
            [potemkin :refer [def-derived-map]]
            [iny.http.date :refer [schedule-date-value-update]]
            [iny.http.method :refer [http-methods get?]]
            [iny.http.status :refer [->status]]
            [iny.http.body :refer [->buffer release]]
            [iny.http1.headers :refer [->headers headers->map headers-with-date]])
  (:import [java.io
            IOException]
           [java.net
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
            HttpUtil
            HttpContent
            LastHttpContent
            HttpRequest
            HttpResponse
            HttpVersion
            HttpHeaderNames
            HttpResponseStatus
            DefaultHttpContent
            DefaultHttpResponse]))

(defn ^ChannelFuture write-response
  [^ChannelHandlerContext ctx
   ^HttpResponse          head
   ^HttpContent           body]
  (.write ctx head (.voidPromise ctx))
  (when (some-> body (.content) (.readableBytes) (pos?))
    (.write ctx body (.voidPromise ctx)))
  (.writeAndFlush ctx LastHttpContent/EMPTY_LAST_CONTENT))

(defn ^ChannelFuture respond-500
  [^ChannelHandlerContext ctx
   ^Throwable             ex]
  (let [error-head (doto (DefaultHttpResponse.
                          HttpVersion/HTTP_1_1
                          HttpResponseStatus/INTERNAL_SERVER_ERROR
                          (headers-with-date))
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
    (write-response ctx response response-body)))

(defn request-method
  [^HttpRequest req]
  (->> req (.method) (.name) (.get http-methods)))

(def-derived-map RingRequest
  [^ChannelHandlerContext ctx
   ^HttpRequest           req
   ^ByteBuf               body
   q-at]
  :uri            (if (not (neg? ^int q-at))
                    (.substring (.uri req) 0 q-at)
                    (.uri req))
  :query-string   (if (not (neg? ^int q-at))
                    (.substring (.uri req) q-at))
  :headers        (headers->map (.headers req))
  :request-method (request-method req)
  :scheme         :http
  :body           (ByteBufInputStream. body false)
  :server-name    (some-> ctx (.channel) ^InetSocketAddress (.localAddress) (.getHostName))
  :server-port    (some-> ctx (.channel) ^InetSocketAddress (.localAddress) (.getPort))
  :remote-addr    (some-> ctx (.channel) ^InetSocketAddress (.remoteAddress) (.getAddress) (.getHostAddress))
  :iny/keep-alive (HttpUtil/isKeepAlive req))

(defn netty->ring-request
  [^ChannelHandlerContext ctx
   ^ByteBuf               body
   ^HttpRequest           req]
  (->RingRequest ctx req body (.indexOf (.uri req) (int 63))))

(defn content-length'
  [^HttpRequest req]
  (when-let [header-value (-> req
                              (.headers)
                              (.get HttpHeaderNames/CONTENT_LENGTH))]
    (try
      (Long/parseLong header-value)
      (catch Throwable e
        (log/debug "Wrong content length header value" e)
        nil))))

(def content-length (memoize content-length'))

(defn content-known-empty?
  [^HttpRequest req]
  (= (content-length req) 0))

(defn ^ChannelInboundHandler http-handler
  [user-handler]
  (let [body-buf (atom nil)
        request (atom nil)]
    (reify
      ChannelInboundHandler

      (handlerAdded [_ ctx])
      (handlerRemoved [_ ctx])
      (exceptionCaught [_ ctx ex]
        (log/error ex)
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
            (if (or (get? msg) (content-known-empty? msg))
              (let [ftr (->> msg
                             (netty->ring-request ctx (->buffer nil))
                             (user-handler)
                             (respond ctx))]
                (when-not (HttpUtil/isKeepAlive msg)
                  (.addListener ftr ChannelFutureListener/CLOSE)))
              (let [allocator (.alloc ctx)
                    buffer (if-let [len (content-length msg)]
                             (.buffer allocator len)
                             (.buffer allocator))]
                (reset! request (netty->ring-request ctx buffer msg))
                (reset! body-buf buffer)))
          (instance? HttpContent msg)
            (when-let [^ByteBuf buffer @body-buf]
              (.writeBytes buffer (.content ^HttpContent msg))
              (when (instance? LastHttpContent msg)
                (->> @request
                     (user-handler)
                     (respond ctx))
                (release buffer)
                (reset! request nil)
                (reset! body-buf nil)))
          ; :else
          ;   (log/info (class msg))
          )
          (release msg))
      (channelReadComplete [_ ctx])
      (userEventTriggered [_ ctx event])
      (channelWritabilityChanged [_ ctx]))))
