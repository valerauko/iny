(ns iny.server
  (:require [clojure.tools.logging :as log]
            [iny.native :refer [event-loop socket-chan]]
            [iny.http2 :as http2]
            [iny.http3 :refer [http3-boot]]
            [iny.ring.handler :as ring])
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
  [user-handler executor]
  (proxy [ChannelInitializer] []
    (initChannel
     [^SocketChannel ch]
     (let [pipeline (.pipeline ch)]
       (.addLast pipeline "optimize-flushes" (FlushConsolidationHandler.))
       (.addLast pipeline executor "ring-handler" (ring/handler user-handler))
       (http2/server-pipeline pipeline executor)))))

(defn server
  [handler]
  (let [total-threads (- (* 2 (.availableProcessors (Runtime/getRuntime))) 3)
        parent-threads (inc (int (Math/floor (/ total-threads 3.0))))
        child-threads (- (+ 2 total-threads) parent-threads)
        socket-chan (socket-chan)
        parent-group (event-loop parent-threads)
        child-group (event-loop (int (Math/floor (/ child-threads 2))))
        user-executor (event-loop (int (Math/ceil (/ child-threads 2))))
        port 8080]
    (log/info (str "Starting Iny server at port " port))
    (try
      (let [boot (doto (ServerBootstrap.)
                       (.option ChannelOption/SO_BACKLOG (int 1024))
                       (.option ChannelOption/SO_REUSEADDR true)
                       (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                       (.option ChannelOption/ALLOCATOR (PooledByteBufAllocator. true))
                       (.group parent-group child-group)
                       (.channel socket-chan)
                       (.childHandler (server-pipeline handler user-executor))
                       (.childOption ChannelOption/SO_REUSEADDR true)
                       (.childOption ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                       (.childOption ChannelOption/TCP_NODELAY true)
                       (.childOption ChannelOption/ALLOCATOR (PooledByteBufAllocator. true)))
            tcp-channel (-> boot (.bind port) .sync .channel)
            udp-channel (-> (http3-boot port parent-group user-executor handler)
                            (.bind port) .sync .channel)]
        (fn closer []
          (-> tcp-channel .close .sync)
          (-> udp-channel .close .sync)
          (.shutdownGracefully parent-group)
          (.shutdownGracefully child-group)
          (.shutdownGracefully user-executor)))
      (catch Exception e
        (log/error "Iny server error:" e)
        @(.shutdownGracefully parent-group)
        @(.shutdownGracefully child-group)
        @(.shutdownGracefully user-executor)))))
