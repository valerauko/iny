(ns iny.http.pipeline
  (:require [iny.http2.pipeline :as http2])
  (:import [io.netty.channel
            ChannelOption
            ChannelInitializer]
           [io.netty.channel.socket
            SocketChannel]
           [io.netty.handler.flush
            FlushConsolidationHandler]))

(defn server-pipeline
  [{:keys [user-handler worker-group] :as options}]
  (proxy [ChannelInitializer] []
    (initChannel
     [^SocketChannel ch]
     (let [pipeline (.pipeline ch)]
       (.addLast pipeline "optimize-flushes" (FlushConsolidationHandler.))
       (.addLast pipeline worker-group "ring-handler" user-handler)
       (http2/server-pipeline pipeline options)))))
