(ns iny.http1.handler
  (:require [clojure.tools.logging :as log]
            [potemkin :refer [def-derived-map]]
            [iny.http.date :refer [schedule-date-value-update]]
            [iny.http.method :refer [http-methods]]
            [iny.http.status :refer [->status]]
            [iny.http.body :refer [->buffer]]
            [iny.http1.headers :refer [->headers headers-with-date]])
  (:import [java.io
            IOException]
           [java.net
            InetSocketAddress]
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
            HttpResponseStatus
            DefaultHttpContent
            DefaultHttpResponse]))

(defn ^ChannelFuture write-response
  [^ChannelHandlerContext ctx
   ^HttpResponse          head
   ^HttpContent           body]
  (.write ctx head (.voidPromise ctx))
  (when (pos? (-> body (.content) (.readableBytes)))
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
  (let []
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
            (when-not (HttpUtil/isTransferEncodingChunked msg)
              (let [ftr (->> msg
                             (netty->ring-request ctx)
                             (user-handler)
                             (respond ctx))]
                (when-not (HttpUtil/isKeepAlive msg)
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
