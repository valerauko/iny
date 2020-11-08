(ns iny.server
  (:require [clojure.tools.logging :as log]
            [iny.native :refer [event-loop socket-chan]]
            ; [iny.http :as http]
            [iny.http2 :as http2])
  (:import [io.netty.util
            ResourceLeakDetector
            ResourceLeakDetector$Level]
           [io.netty.bootstrap
            ServerBootstrap]
           [io.netty.buffer
            PooledByteBufAllocator]
           [io.netty.channel
            ChannelOption
            ChannelInitializer]
           [io.netty.channel.socket
            SocketChannel]
           [io.netty.handler.flush
            FlushConsolidationHandler]
           [io.netty.handler.codec.http
            HttpServerCodec
            HttpServerUpgradeHandler]))

(ResourceLeakDetector/setLevel ResourceLeakDetector$Level/DISABLED)

(defn server-pipeline
  [user-handler]
  (let [build-pipeline http2/server-pipeline]
    (proxy [ChannelInitializer] []
      (initChannel
       [^SocketChannel ch]
       (let [pipeline (.pipeline ch)]
         (.addLast pipeline "optimize-flushes" (FlushConsolidationHandler.))
         (build-pipeline pipeline user-handler))))))

(defn server
  [handler]
  (let [cores (- (.availableProcessors (Runtime/getRuntime)) 3)
        parent-threads (inc (int (Math/floor (/ cores 3.0))))
        child-threads (- (+ 2 cores) parent-threads)
        socket-chan (socket-chan)
        parent-group (event-loop parent-threads)
        child-group (event-loop child-threads)
        port 8080]
    (try
      (let [boot (doto (ServerBootstrap.)
                       (.option ChannelOption/SO_BACKLOG (int 1024))
                       (.option ChannelOption/SO_REUSEADDR true)
                       (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                       (.option ChannelOption/ALLOCATOR (PooledByteBufAllocator. true))
                       (.group parent-group child-group)
                       (.channel socket-chan)
                       (.childHandler (server-pipeline handler))
                       (.childOption ChannelOption/SO_REUSEADDR true)
                       (.childOption ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                       (.childOption ChannelOption/TCP_NODELAY true)
                       (.childOption ChannelOption/ALLOCATOR (PooledByteBufAllocator. true)))
            channel (-> boot (.bind port) .sync .channel)]
        (fn closer []
          (-> channel .close .sync)
          (.shutdownGracefully parent-group)
          (.shutdownGracefully child-group)))
      (catch Exception e
        (log/error e)
        @(.shutdownGracefully parent-group)
        @(.shutdownGracefully child-group)))))
