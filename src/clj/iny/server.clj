(ns iny.server
  (:require [clojure.tools.logging :as log]
            [iny.http :as http]
            [iny.http2 :as http2])
  (:import [io.netty.util
            ReferenceCountUtil]
           [io.netty.bootstrap
            ServerBootstrap]
           [io.netty.buffer
            PooledByteBufAllocator]
           [io.netty.channel
            MultithreadEventLoopGroup
            ChannelOption
            ChannelInitializer
            ChannelHandler
            ChannelInboundHandler]
           [io.netty.channel.epoll
            Epoll
            EpollEventLoopGroup
            EpollServerSocketChannel]
           [io.netty.channel.nio
            NioEventLoopGroup]
           [io.netty.channel.socket
            SocketChannel]
           [io.netty.channel.socket.nio
            NioServerSocketChannel]
           [io.netty.handler.flush
            FlushConsolidationHandler]
           [io.netty.handler.codec.http
            HttpServerCodec
            HttpServerUpgradeHandler]))

(defn http-fallback
  [user-handler]
  (reify
    ChannelInboundHandler
    (handlerAdded [_ _])
    (handlerRemoved [_ _])
    (exceptionCaught [_ ctx ex]
      (log/error ex))
    (channelRegistered [_ ctx])
    (channelUnregistered [_ ctx])
    (channelActive [_ ctx])
    (channelInactive [_ ctx])
    (channelRead
     [this ctx msg]
     (let [pipeline (.pipeline ctx)]
       ;; removes the h2c-upgrade handler (no upgrade was attempted)
       (.remove pipeline HttpServerCodec)
       (.remove pipeline HttpServerUpgradeHandler)
       (http/build-http11-pipeline pipeline user-handler)
       (.remove pipeline this)
       (.fireChannelRead ctx (ReferenceCountUtil/retain msg))))
    (channelReadComplete [_ ctx])
    (userEventTriggered [_ ctx event])
    (channelWritabilityChanged [_ ctx])))

(defn server-pipeline
  [user-handler]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel ch]
      (let [pipeline (.pipeline ch)]
        (.addLast pipeline "optimize-flushes" (FlushConsolidationHandler.))
        (.addLast pipeline "h2c-upgrade" ^ChannelHandler (http2/h2c-upgrade user-handler))
        (.addLast pipeline "http-fallback" ^ChannelHandler (http-fallback user-handler))))))

(defn server
  [handler]
  (let [cores (- (.availableProcessors (Runtime/getRuntime)) 3)
        parent-threads (inc (int (Math/floor (/ cores 3.0))))
        child-threads (- (+ 2 cores) parent-threads)
        epoll? (Epoll/isAvailable)
        socket-chan (if epoll? EpollServerSocketChannel NioServerSocketChannel)
        parent-group (if epoll?
                       (EpollEventLoopGroup. parent-threads)
                       (NioEventLoopGroup. parent-threads))
        child-group (if epoll?
                      (EpollEventLoopGroup. child-threads)
                      (NioEventLoopGroup. child-threads))
        port 8080]
    (try
      (let [boot (doto (ServerBootstrap.)
                       (.option ChannelOption/SO_BACKLOG (int 1024))
                       (.option ChannelOption/SO_REUSEADDR true)
                       (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                       (.option ChannelOption/ALLOCATOR (PooledByteBufAllocator. true))
                       (.group ^MultithreadEventLoopGroup parent-group
                               ^MultithreadEventLoopGroup child-group)
                       (.channel socket-chan)
                       (.childHandler (server-pipeline handler))
                       (.childOption ChannelOption/SO_REUSEADDR true)
                       (.childOption ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                       (.childOption ChannelOption/TCP_NODELAY true)
                       (.childOption ChannelOption/ALLOCATOR (PooledByteBufAllocator. true)))
            channel (-> boot (.bind port) .sync .channel)]
        (fn closer []
          (-> channel .close .sync)
          (.shutdownGracefully ^MultithreadEventLoopGroup parent-group)
          (.shutdownGracefully ^MultithreadEventLoopGroup child-group)))
      (catch Exception e
        @(.shutdownGracefully ^MultithreadEventLoopGroup parent-group)
        @(.shutdownGracefully ^MultithreadEventLoopGroup child-group)))))
