(ns iny.http.channel
  (:require [clojure.tools.logging :as log]
            [iny.native :refer [socket-chan]]
            [iny.http2 :as http2])
  (:import [io.netty.bootstrap
            ServerBootstrap]
           [io.netty.channel
            ChannelOption
            ChannelInitializer]
           [io.netty.channel.socket
            SocketChannel]
           [io.netty.buffer
            PooledByteBufAllocator]
           [io.netty.handler.flush
            FlushConsolidationHandler]))

(defn server-pipeline
  [{:keys [user-handler worker-group]}]
  (proxy [ChannelInitializer] []
    (initChannel
     [^SocketChannel ch]
     (let [pipeline (.pipeline ch)]
       (.addLast pipeline "optimize-flushes" (FlushConsolidationHandler.))
       (.addLast pipeline worker-group "ring-handler" user-handler)
       (http2/server-pipeline pipeline)))))

(defn ^ServerBootstrap bootstrap
  [{:keys [user-handler parent-group child-group worker-group] :as options}]
  (doto (ServerBootstrap.)
        (.option ChannelOption/SO_BACKLOG (int 1024))
        (.option ChannelOption/SO_REUSEADDR true)
        (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
        (.option ChannelOption/ALLOCATOR (PooledByteBufAllocator. true))
        (.group parent-group child-group)
        (.channel (socket-chan))
        (.childHandler (server-pipeline options))
        (.childOption ChannelOption/SO_REUSEADDR true)
        (.childOption ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
        (.childOption ChannelOption/TCP_NODELAY true)
        (.childOption ChannelOption/ALLOCATOR (PooledByteBufAllocator. true))))
