(ns iny.http.pipeline
  (:require [iny.http1.pipeline :as http1]
            [iny.http2.pipeline :as http2])
  (:import [io.netty.channel
            ChannelOption
            ChannelInitializer]
           [io.netty.channel.socket
            SocketChannel]
           [io.netty.handler.flush
            FlushConsolidationHandler]))

(defn server-pipeline
  [{:keys [user-handler worker-group http2] :as options}]
  (proxy [ChannelInitializer] []
    (initChannel
     [^SocketChannel ch]
     (let [pipeline (.pipeline ch)]
       (.addLast pipeline "optimize-flushes" (FlushConsolidationHandler.))
       (.addLast pipeline worker-group "ring-handler" user-handler)
       (if http2
         (http2/server-pipeline pipeline options)
         (http1/server-pipeline pipeline options))))))
