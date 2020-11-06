(ns iny.http2.handler
  (:require [clojure.tools.logging :as log]
            [iny.http.handler :refer [->buffer]])
  (:import [io.netty.channel
            ChannelInboundHandler]
           [io.netty.handler.codec.http
            HttpResponseStatus]
           [io.netty.handler.codec.http2
            Http2FrameCodec
            ;; frames
            Http2DataFrame
            Http2HeadersFrame
            DefaultHttp2DataFrame
            DefaultHttp2HeadersFrame
            ;;
            DefaultHttp2Headers]))

(defn http2-handler
  [user-handler frame-codec]
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
    (channelRegistered [_ ctx])
    (channelUnregistered [_ ctx])
    (channelActive [_ ctx])
    (channelInactive [_ ctx])
    (channelRead [_ ctx msg]
      (cond
        (instance? Http2HeadersFrame msg)
          (let [stream (.stream ^Http2HeadersFrame msg)]
            (.write ctx
                    (doto (DefaultHttp2HeadersFrame.
                           (doto (DefaultHttp2Headers.)
                                 (.status (.codeAsText ^HttpResponseStatus
                                                       HttpResponseStatus/OK))))
                          (.stream stream))
                    (.voidPromise ctx))
            (.writeAndFlush ctx
                            (doto (DefaultHttp2DataFrame.
                                   (->buffer "hello, world")
                                   true)
                                  (.stream stream))
                            (.voidPromise ctx)))
        ; (instance? Http2DataFrame msg)
        ;   ,,,
        ; :else
        ;   (log/info (class msg))
        ))
    (channelReadComplete [_ ctx])
    (userEventTriggered [_ ctx event])
    (channelWritabilityChanged [_ ctx])))
