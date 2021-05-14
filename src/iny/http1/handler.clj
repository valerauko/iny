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

(defn netty->ring-request
  [^ChannelHandlerContext ctx
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

(def data-factory
  (DefaultHttpDataFactory. DefaultHttpDataFactory/MINSIZE))

(defprotocol LogUpload
  (log-it [_]))

(extend-protocol LogUpload
  Attribute
  (log-it [^Attribute attr]
   (log/info {(.getName attr) (.getValue attr)}))

  FileUpload
  (log-it [^FileUpload upload]
   (log/info
    {:field-name (.getName upload)
     :filename (.getFilename upload)
     :content-type (.getContentType upload)
     :contents (if (.isInMemory upload)
                 (.getByteBuf upload)
                 (.getFile upload))
     :size (.definedLength upload)})))

(defn ^ChannelHandler http-handler
  [executor]
  (let [stream (atom nil)
        body-decoder (atom nil)
        keep-alive? (atom false)
        out-name "iny-http1-outbound"]
    (handler/inbound
      (handlerAdded [_ ctx]
        (let [pipeline (.pipeline ctx)
              outbound
              (handler/outbound
                (write [_ ctx msg promise]
                  (if (map? msg)
                    (do
                      (let [ftr ^ChannelFuture (respond ctx msg)]
                        (.addListener
                         ftr
                         ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)
                        (when-not @keep-alive?
                          (.addListener ftr ChannelFutureListener/CLOSE)))
                      (when-let [decoder @body-decoder]
                        (.destroy ^HttpPostRequestDecoder decoder)
                        (reset! body-decoder nil))
                      (when-let [out-stream @stream]
                        (.close ^OutputStream out-stream)
                        (reset! stream nil)))
                    (.write ctx msg promise))))]
          (.addBefore pipeline executor "ring-handler" out-name outbound)))
      (handlerRemoved [_ ctx]
        (let [pipeline (.pipeline ctx)]
          (when (.get pipeline out-name)
            (.remove pipeline out-name))))
      (exceptionCaught [_ ctx ex]
        (log/error ex)
        (when-not (or (instance? IOException ex)
                      (instance? RejectedExecutionException ex))
          (respond-500 ctx ex))
        (.close ctx))
      (channelRegistered [_ ctx]
        (schedule-date-value-update ctx))
      (channelRead [_ ctx msg]
        (cond
          (instance? HttpRequest msg)
          (let [keep-alive (reset! keep-alive? (HttpUtil/isKeepAlive msg))]
            (cond
              ;; request without body
              (or (get? msg) (content-known-empty? msg))
              (.fireChannelRead
               ctx
               (netty->ring-request ctx (InputStream/nullInputStream) msg))

              ;; TODO: figure out how to pass multipart
              (HttpPostRequestDecoder/isMultipart msg)
              (let [decoder-instance (HttpPostRequestDecoder. data-factory msg)
                    request (netty->ring-request
                             ctx
                             (InputStream/nullInputStream)
                             msg)]
                (.fireChannelRead ctx request)
                (reset! body-decoder decoder-instance))

              ;; non-multipart request with body
              :else
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
                   (netty->ring-request ctx (InputStream/nullInputStream) msg))))))

          (instance? HttpContent msg)
          (cond-let
            [^PipedOutputStream out-stream @stream]
            (let [buf (.content ^HttpContent msg)
                  len (.readableBytes buf)]
              (try
                (.getBytes buf 0 out-stream len)
                (catch IOException _))
              (when (instance? LastHttpContent msg)
                (.close out-stream)
                (reset! stream nil)
                (.setAutoRead (.config (.channel ctx)) true)))

            [^HttpPostRequestDecoder decoder @body-decoder]
            (.offer decoder ^HttpContent msg)))

          ; :else
          ; (log/info (class msg)))

        (release msg)))))
