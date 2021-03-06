(ns iny.http1.handler
  (:require [clojure.tools.logging :as log]
            [potemkin :refer [def-derived-map reify-map-type]]
            [iny.netty.handler :as handler]
            [iny.ring.request :refer [->RingRequest]]
            [iny.http.date :refer [schedule-date-value-update]]
            [iny.http.method :refer [http-methods get?]]
            [iny.http.status :refer [->status]]
            [iny.http.body :refer [->buffer release]]
            [iny.native :as native]
            [iny.http1.headers :refer [->headers headers->map headers-with-date]])
  (:import [java.io
            InputStream
            IOException
            OutputStream
            PipedInputStream
            PipedOutputStream]
           [java.net
            InetSocketAddress]
           [java.util.concurrent
            RejectedExecutionException]
           [io.netty.util.concurrent
            ScheduledFuture]
           [io.netty.channel
            ChannelFuture
            ChannelFutureListener
            ChannelHandler
            ChannelHandlerContext
            ChannelInboundHandler
            ChannelOutboundHandler]
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
            DefaultHttpResponse]
           [io.netty.handler.codec.http.multipart
            DefaultHttpDataFactory
            HttpPostRequestDecoder
            InterfaceHttpData
            InterfaceHttpData$HttpDataType
            Attribute
            FileUpload]))

(defmacro cond-let
  [& clauses]
  (when clauses
    (list 'if-let (first clauses)
          (if (next clauses)
            (second clauses)
            (throw (IllegalArgumentException.
                    "cond-let requires an even number of forms")))
          (cons 'iny.http1.handler/cond-let (next (next clauses))))))

(defn ^ChannelFuture write-response
  [^ChannelHandlerContext ctx
   ^HttpResponse          head
   ^HttpContent           body]
  (.write ctx head (.voidPromise ctx))
  ;; want to use some-> here but then pos? gets boxed...
  (when (and body (-> body (.content) (.readableBytes) (pos?)))
    (.write ctx body (.voidPromise ctx)))
  (.writeAndFlush ctx LastHttpContent/EMPTY_LAST_CONTENT))

(defn ^ChannelFuture respond-500
  [^ChannelHandlerContext ctx
   opts
   ^Throwable             ex]
  (let [error-head (doto (DefaultHttpResponse.
                          HttpVersion/HTTP_1_1
                          HttpResponseStatus/INTERNAL_SERVER_ERROR
                          (headers-with-date opts))
                         (HttpUtil/setContentLength 0))]
    (write-response ctx error-head nil)))

(defn ^ChannelFuture respond
  [^ChannelHandlerContext ctx opts
   {:keys [body headers status]}]
  (let [status (->status status)
        buffer (->buffer body ctx)
        headers (->headers headers opts)
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

(defn netty->ring-request
  [^ChannelHandlerContext ctx
   _opts
   ^InputStream           body
   ^HttpRequest           req]
  (let [uri (.uri req)]
    (->RingRequest
     ctx
     uri
     #(headers->map (.headers req))
     #(request-method req)
     #(identity :http)
     body
     (.indexOf uri (int 63)))))

(defn ^Long content-length
  [^HttpRequest req]
  (when-let [header-value (-> req (.headers) (.get HttpHeaderNames/CONTENT_LENGTH))]
    (try
      (Long/parseLong header-value)
      (catch Throwable e
        (log/debug "Wrong content length header value" e)))))

(defn content-known-empty?
  [^HttpRequest req]
  (= (content-length req) 0))

(defn ^ChannelHandler http-handler
  [opts]
  (let [stream (atom nil)
        keep-alive? (atom false)
        date-future (atom nil)
        out-name "iny-http1-outbound"]
    (handler/inbound
      (handlerAdded [_ ctx]
        (let [pipeline (.pipeline ctx)
              ring-executor (-> pipeline (.context "ring-handler") (.executor))
              outbound
              (handler/outbound
                (write [_ ctx msg promise]
                  (if (map? msg)
                    (do
                      (let [ftr ^ChannelFuture (respond ctx opts msg)]
                        (.addListener
                         ftr
                         ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)
                        (when-not @keep-alive?
                          (.addListener ftr ChannelFutureListener/CLOSE)))
                      (when-let [out-stream @stream]
                        (.close ^OutputStream out-stream)
                        (reset! stream nil)))
                    (.write ctx msg promise))))]
          (.addBefore pipeline ring-executor "ring-handler" out-name outbound)))
      (handlerRemoved [_ ctx]
        (let [pipeline (.pipeline ctx)]
          (when (.get pipeline out-name)
            (.remove pipeline out-name))))
      (exceptionCaught [_ ctx ex]
        (log/error ex)
        (when-not (or (instance? IOException ex)
                      (instance? RejectedExecutionException ex))
          (respond-500 ctx opts ex))
        (.close ctx))
      (channelRegistered [_ ctx]
        (reset! date-future (schedule-date-value-update ctx opts)))
      (channelUnregistered [_ ctx]
        (when-let [ftr (first (reset-vals! date-future nil))]
          (.cancel ^ScheduledFuture ftr false)))
      (channelRead [_ ctx msg]
        (cond
          (instance? HttpRequest msg)
          (let [keep-alive (reset! keep-alive? (HttpUtil/isKeepAlive msg))]
            (if (or (get? msg) (content-known-empty? msg))
              ;; request without body
              (.fireChannelRead
               ctx
               (netty->ring-request ctx opts (InputStream/nullInputStream) msg))

              ;; else request with body
              (let [^long len (or (content-length msg) 65536)]
                (if (> len 0)
                  (let [^PipedInputStream in-stream (PipedInputStream. len)
                        out-stream (PipedOutputStream. in-stream)
                        request (netty->ring-request ctx in-stream msg)]
                    (reset! stream out-stream)
                    (.fireChannelRead ctx request)
                    (.setAutoRead (.config (.channel ctx)) false))
                  (.fireChannelRead
                   ctx
                   (netty->ring-request ctx opts (InputStream/nullInputStream) msg))))))

          (instance? HttpContent msg)
          (when-let [^PipedOutputStream out-stream @stream]
            (let [buf (.content ^HttpContent msg)
                  len (.readableBytes buf)]
              (try
                (.getBytes buf 0 out-stream len)
                (catch IOException _))
              (when (instance? LastHttpContent msg)
                (.close out-stream)
                (reset! stream nil)
                (.setAutoRead (.config (.channel ctx)) true)))))

        (release msg)))))
