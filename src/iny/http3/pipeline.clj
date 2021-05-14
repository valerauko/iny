(ns iny.http3.pipeline
  (:require [iny.http1.handler :refer [http-handler]])
  (:import [io.netty.channel
            ChannelInitializer]
           [io.netty.incubator.codec.http3
            Http3
            Http3FrameToHttpObjectCodec
            Http3ServerConnectionHandler]
           [io.netty.incubator.codec.quic
            InsecureQuicTokenHandler
            QuicChannel
            QuicStreamChannel]))

(defn init-stream
  [worker-group user-handler]
  (proxy [ChannelInitializer] []
    (initChannel [^QuicStreamChannel ch]
      (let [pipeline (.pipeline ch)]
        (.addLast pipeline "http3-codec" (Http3FrameToHttpObjectCodec. true))
        (.addLast pipeline worker-group "ring-handler" user-handler)
        (.addBefore pipeline "ring-handler" "http-handler" (http-handler worker-group))))))

(defn init-connection
  [worker-group user-handler]
  (proxy [ChannelInitializer] []
    (initChannel [^QuicChannel ch]
      (.addLast
       (.pipeline ch)
       "http3-cxn-handler"
       (Http3ServerConnectionHandler.
        (init-stream worker-group user-handler))))))
