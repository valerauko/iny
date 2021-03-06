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
  (:import [clojure.lang
            IPersistentMap]
           [java.io
            InputStream
            IOException
            PipedInputStream
            PipedOutputStream]
           [java.util.concurrent
            RejectedExecutionException]
           [io.netty.util
            AsciiString]
           [io.netty.util.concurrent
            ScheduledFuture]
           [io.netty.channel
            ChannelFuture
            ChannelFutureListener
            ChannelHandlerContext]
           [io.netty.handler.codec.http
            HttpHeaderNames]
           [io.netty.handler.codec.http2
            Http2ChannelDuplexHandler
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
            DefaultHttp2PushPromiseFrame
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
   _opts
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

(defn push-response
  [^ChannelHandlerContext ctx opts ^Http2FrameStream stream ^String path]
  (let [duplex ^Http2ChannelDuplexHandler (.get (.pipeline ctx) "http2-duplex")
        push-headers (doto (DefaultHttp2Headers. false)
                           (.method "GET")
                           (.scheme "https")
                           (.authority "localhost:8080")
                           (.path path))
        push-stream (.newStream duplex)
        promise-frame (doto (DefaultHttp2PushPromiseFrame. push-headers)
                            (.stream stream)
                            (.pushStream push-stream))]
      (doto (.write ctx promise-frame)
            (.addListener ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)
            (.addListener
             (reify ChannelFutureListener
              (operationComplete [_ ftr]
                (.fireChannelRead
                 ctx
                 (assoc
                  (netty->ring-request
                   ctx
                   opts
                   (InputStream/nullInputStream)
                   (DefaultHttp2HeadersFrame. push-headers))
                  :iny.http2/stream push-stream))))))))

(defn ^ChannelFuture respond
  [^ChannelHandlerContext ctx opts ^Http2FrameStream stream
   {:keys [body headers status]
    :iny.http2/keys [push]}]
  (let [status (->status status)
        buffer (when body (->buffer body ctx))
        headers (doto (->headers ^IPersistentMap headers opts)
                      (.set (.value Http2Headers$PseudoHeaderName/STATUS)
                            (.codeAsText status))
                      (.set HttpHeaderNames/CONTENT_LENGTH
                            (if buffer
                              (String/valueOf (.readableBytes buffer))
                              "0")))
        headers-frame (doto (DefaultHttp2HeadersFrame. headers)
                            (.stream stream))]
    (.write ctx headers-frame (.voidPromise ctx))
    (when push (doseq [path push] (push-response ctx opts stream path)))
    (if body
      (let [data-frame (doto (DefaultHttp2DataFrame. buffer true)
                             (.stream stream))]
        (.writeAndFlush ctx data-frame))
      (.flush ctx))))

(defn ^Long content-length
  [^Http2HeadersFrame req]
  (when-let [header-value (-> req (.headers) (.get "content-length"))]
    (try
      (cond
        (instance? AsciiString header-value)
        (.parseLong ^AsciiString header-value)

        (instance? String header-value)
        (Long/parseLong ^String header-value))
      (catch Throwable e
        (log/debug "Wrong content length header value" e)))))

(defn send-away
  ([^ChannelHandlerContext ctx _opts]
   (let [frame (DefaultHttp2GoAwayFrame. Http2Error/PROTOCOL_ERROR)]
     (.writeAndFlush ctx frame)))
  ([^ChannelHandlerContext ctx _opts ^Http2FrameStream stream]
   (let [frame (doto (DefaultHttp2ResetFrame. Http2Error/PROTOCOL_ERROR)
                     (.stream stream))]
     (.writeAndFlush ctx frame))))

(defn expand-window
  [^ChannelHandlerContext ctx _opts ^Http2DataFrame data]
  (let [update-bytes (.initialFlowControlledBytes data)
        request-stream (.stream data)
        update-frame (doto (DefaultHttp2WindowUpdateFrame. update-bytes)
                           (.stream request-stream))]
    (.write ctx update-frame)))

(defn send-no-thanks
  [^ChannelHandlerContext ctx _opts ^Http2FrameStream stream]
  (let [frame (doto (DefaultHttp2ResetFrame. Http2Error/CANCEL)
                    (.stream stream))]
    (.write ctx frame)))

(defn http2-handler
  [opts]
  (let [;; TODO: deal with multiple streams uploading parallel
        ;;   maybe with Http2MultiplexHandler?
        http-stream (atom nil)
        body-stream (atom nil)
        date-future (atom nil)
        responded? (atom false)
        out-name "iny-http2-outbound"]
    (handler/inbound
      (handlerAdded
       [_ ctx]
       (reset! date-future (schedule-date-value-update ctx opts))
       (let [pipeline (.pipeline ctx)
             ring-executor (-> pipeline (.context "ring-handler") (.executor))
             outbound
             (handler/outbound
              (exceptionCaught [_ ctx ex]
                (log/error ex)
                (.close ctx))
              (write [_ ctx msg promise]
                (if (map? msg)
                  (doto (respond ctx
                                 opts
                                 (or (:iny.http2/stream msg) @http-stream)
                                 msg)
                    (.addListener ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)
                    (.addListener (reify ChannelFutureListener
                                    (operationComplete [_ _]
                                      (reset! responded? true)))))
                  (.write ctx msg promise))))]
         (.addBefore pipeline ring-executor "ring-handler" out-name outbound)))
      (handlerRemoved
       [_ ctx]
       (when-let [ftr (first (reset-vals! date-future nil))]
         (.cancel ^ScheduledFuture ftr false))
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
         (send-away ctx opts (.stream ^Http2FrameStreamException ex))

         (instance? RejectedExecutionException ex)
         (respond ctx opts nil {:status 503}))
       (.close ctx))
      (channelRead
       [_ ctx msg]
       (cond
         (instance? Http2HeadersFrame msg)
         (let [msg ^Http2HeadersFrame msg
               stream (reset! http-stream (.stream msg))]
           (reset! responded? false)
           (cond
             ;; request without body
             (.isEndStream msg)
             ;; TODO: handle trailing headers
             ;; this is the "hot path," body-less GET requests
             (.fireChannelRead
               ctx
               (netty->ring-request ctx opts (InputStream/nullInputStream) msg))

             ;; request with body
             ;; netty's HttpPostRequestDecoder can't handle http/2 frames
             :else
             (let [^long len (or (content-length msg) 65536)]
               (if (> len 0)
                 (let [^PipedInputStream in-stream (PipedInputStream. len)
                       out-stream (PipedOutputStream. in-stream)
                       request (netty->ring-request ctx opts in-stream msg)]
                   (reset! body-stream out-stream)
                   (.fireChannelRead ctx request)
                   (.setAutoRead (.config (.channel ctx)) false))
                 ;; in a funky edge-case it may be an empty body
                 (.fireChannelRead
                  ctx
                  (netty->ring-request ctx opts (InputStream/nullInputStream) msg))))))

         ;; body frames
         (instance? Http2DataFrame msg)
         (let [msg ^Http2DataFrame msg]
           (if @responded?
             (send-no-thanks ctx opts @http-stream)
             (when-let [out-stream ^PipedOutputStream @body-stream]
               (let [buf (.content msg)
                     len (.readableBytes buf)]
                 (try
                   (.getBytes buf 0 out-stream len)
                   (catch IOException _))
                 (.addListener ^ChannelFuture (expand-window ctx opts msg)
                               ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)
                 (when (.isEndStream msg)
                   (.close out-stream)
                   (.setAutoRead (.config (.channel ctx)) true)
                   (reset! body-stream nil)))))))

       (release msg)))))
