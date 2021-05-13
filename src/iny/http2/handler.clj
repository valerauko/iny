(ns iny.http2.handler
  (:require [clojure.tools.logging :as log]
            [potemkin :refer [def-derived-map]]
            [iny.netty.handler :as handler]
            [iny.ring.request :refer [->RingRequest]]
            [iny.http.date :refer [schedule-date-value-update]]
            [iny.http.method :refer [http-methods]]
            [iny.http.status :refer [->status]]
            [iny.http.body :refer [->buffer release]]
            [iny.http2.headers :refer [->headers headers->map]])
  (:import [java.io
            InputStream
            IOException
            PipedInputStream
            PipedOutputStream]
           [java.util.concurrent
            RejectedExecutionException]
           [io.netty.util
            AsciiString]
           [io.netty.channel
            ChannelFuture
            ChannelFutureListener
            ChannelHandlerContext]
           [io.netty.handler.codec.http
            HttpHeaderNames]
           [io.netty.handler.codec.http2
            Http2Error
            Http2Exception
            Http2FrameCodec
            Http2FrameStream
            Http2FrameStreamException
            ;; frames
            Http2DataFrame
            Http2HeadersFrame
            DefaultHttp2DataFrame
            DefaultHttp2GoAwayFrame
            DefaultHttp2HeadersFrame
            DefaultHttp2ResetFrame
            DefaultHttp2WindowUpdateFrame
            ;;
            Http2Headers
            Http2Headers$PseudoHeaderName
            DefaultHttp2Headers]))

(defn request-method
  [^Http2Headers headers]
  (->> headers (.method) (str) (.get http-methods)))

(defn netty->ring-request
  [^ChannelHandlerContext ctx
   ^InputStream           body
   ^Http2HeadersFrame     req]
  (let [headers (.headers req)
        path (.toString (.path headers))]
    (->RingRequest
     ctx
     path
     #(headers->map headers)
     #(str (request-method headers))
     #(-> headers (.scheme) (str) (keyword))
     body
     (.indexOf path (int 63)))))

(defn ^ChannelFuture respond
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
    (if body
      (let [data-frame (doto (DefaultHttp2DataFrame. buffer true)
                             (.stream stream))]
        (.writeAndFlush ctx data-frame))
      (.flush ctx))))

(defn content-length
  [^Http2HeadersFrame req]
  (if-let [header-value (-> req (.headers) (.get "content-length"))]
    (try
      (cond
        (instance? AsciiString header-value)
        (.parseLong ^AsciiString header-value)

        (instance? String header-value)
        (Long/parseLong ^String header-value)

        :else 0)
      (catch Throwable e
        (log/debug "Wrong content length header value" e)
        0))
    0))

(defn send-away
  ([^ChannelHandlerContext ctx]
   (let [frame (DefaultHttp2GoAwayFrame. Http2Error/PROTOCOL_ERROR)]
     (.writeAndFlush ctx frame)))
  ([^ChannelHandlerContext ctx ^Http2FrameStream stream]
   (let [frame (doto (DefaultHttp2ResetFrame. Http2Error/PROTOCOL_ERROR)
                     (.stream stream))]
     (.writeAndFlush ctx frame))))

(defn expand-window
  [^ChannelHandlerContext ctx ^Http2DataFrame data]
  (let [update-bytes (.initialFlowControlledBytes data)
        request-stream (.stream data)
        update-frame (doto (DefaultHttp2WindowUpdateFrame. update-bytes)
                           (.stream request-stream))]
    (.write ctx update-frame)))

(defn send-no-thanks
  [^ChannelHandlerContext ctx ^Http2FrameStream stream]
  (let [frame (doto (DefaultHttp2ResetFrame. Http2Error/CANCEL)
                    (.stream stream))]
    (.write ctx frame)))

(defn http2-handler
  [frame-codec executor]
  (let [;; TODO: deal with multiple streams uploading parallel
        ;;   maybe with Http2MultiplexHandler?
        http-stream (atom nil)
        body-stream (atom nil)
        responded? (atom false)
        out-name "iny-http2-outbound"]
    (handler/inbound
      (handlerAdded
       [_ ctx]
       (let [pipeline (.pipeline ctx)]
         (when-let [fallback (.get pipeline "http-fallback")]
           (.remove pipeline fallback))
         ;; add bidi codec handler to the pipeline if not present yet
         (when-not (.get pipeline Http2FrameCodec)
           (.addBefore pipeline (.name ctx) nil frame-codec))
         (let [outbound
               (handler/outbound
                (exceptionCaught [_ ctx ex]
                  (log/error ex)
                  (.close ctx))
                (write [_ ctx msg promise]
                  (if (map? msg)
                    (doto (respond ctx @http-stream msg)
                          (.addListener ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)
                          (.addListener (reify ChannelFutureListener
                                          (operationComplete [_ _]
                                            (reset! responded? true)))))
                    (.write ctx msg promise))))]
           (.addBefore pipeline executor "ring-handler" out-name outbound))))
      (handlerRemoved
       [_ ctx]
       (let [pipeline (.pipeline ctx)]
         (when (.get pipeline out-name)
           (.remove pipeline out-name))))
      (exceptionCaught
       [_ ctx ex]
       (when-not (or (instance? Http2FrameStreamException ex)
                     (instance? Http2Exception ex)
                     (instance? IOException ex))
         (log/error ex))
       (cond
         (instance? Http2FrameStreamException ex)
         (send-away ctx (.stream ^Http2FrameStreamException ex))

         (instance? RejectedExecutionException ex)
         (respond ctx nil {:status 503}))
       (.close ctx))
      (channelRegistered
       [_ ctx]
       (schedule-date-value-update ctx))
      (channelRead
       [_ ctx msg]
       (cond
         (instance? Http2HeadersFrame msg)
         (let [msg ^Http2HeadersFrame msg
               stream (.stream msg)]
           (reset! http-stream stream)
           (reset! responded? false)
           (cond
             ;; request without body
             (.isEndStream msg)
             ;; TODO: handle trailing headers
             ;; this is the "hot path," body-less GET requests
             (.fireChannelRead
               ctx
               (netty->ring-request
                ctx
                (InputStream/nullInputStream)
                msg))

             ;; request with body
             ;; netty's HttpPostRequestDecoder can't handle http/2 frames
             :else
             (let [len (content-length msg)]
               (if (> len 0)
                 (let [in-stream ^PipedInputStream (PipedInputStream. ^long len)
                       out-stream (PipedOutputStream. in-stream)
                       request (netty->ring-request ctx in-stream msg)]
                   (reset! body-stream out-stream)
                   (.fireChannelRead ctx request)
                   (.setAutoRead (.config (.channel ctx)) false))
                 ;; in a funky edge-case it may be an empty body
                 (.fireChannelRead
                  ctx
                  (netty->ring-request
                   ctx
                   (InputStream/nullInputStream)
                   msg))))))

         ;; body frames
         (instance? Http2DataFrame msg)
         (let [msg ^Http2DataFrame msg]
           (if @responded?
             (send-no-thanks ctx @http-stream)
             (when-let [out-stream ^PipedOutputStream @body-stream]
               (let [buf (.content msg)
                     len (.readableBytes buf)]
                 (try
                   (.getBytes buf 0 out-stream len)
                   (catch IOException _))
                 (.addListener ^ChannelFuture (expand-window ctx msg)
                               ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)
                 (when (.isEndStream msg)
                   (.close out-stream)
                   (.setAutoRead (.config (.channel ctx)) true)
                   (reset! body-stream nil)))))))

       (release msg)))))
