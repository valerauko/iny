(ns iny.channel.tcp
  (:require [clojure.tools.logging :as log]
            [iny.native :refer [socket-chan]]
            [iny.http.pipeline :refer [server-pipeline]])
  (:import [io.netty.bootstrap
            ServerBootstrap]
           [io.netty.channel
            ChannelOption]
           [io.netty.buffer
            PooledByteBufAllocator]))
           ; [io.netty.handler.logging
           ;  LogLevel
           ;  LoggingHandler]))

(defn ^ServerBootstrap bootstrap
  [{:keys [user-handler parent-group child-group worker-group] :as options}]
  (doto (ServerBootstrap.)
        (.option ChannelOption/SO_BACKLOG (int 1024))
        (.option ChannelOption/SO_REUSEADDR true)
        (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
        (.option ChannelOption/ALLOCATOR (PooledByteBufAllocator. true))
        ; (.handler (LoggingHandler. LogLevel/DEBUG))
        (.group parent-group child-group)
        (.channel (socket-chan))
        (.childHandler (server-pipeline options))
        (.childOption ChannelOption/SO_REUSEADDR true)
        (.childOption ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
        (.childOption ChannelOption/TCP_NODELAY true)
        (.childOption ChannelOption/ALLOCATOR (PooledByteBufAllocator. true))))
