(ns iny.native
  (:require [clojure.tools.logging :as log])
  (:import [io.netty.util
            Version]
           [io.netty.util.internal
            PlatformDependent]
           [io.netty.channel
            MultithreadEventLoopGroup]
           ; [io.netty.channel.epoll
           ;  Epoll
           ;  EpollEventLoopGroup
           ;  EpollServerSocketChannel]
           ; [io.netty.channel.kqueue
           ;  KQueue
           ;  KQueueEventLoopGroup
           ;  KQueueServerSocketChannel]
           [io.netty.channel.nio
            NioEventLoopGroup]
           [io.netty.channel.socket.nio
            NioServerSocketChannel]))

(defn version-of
  [netty-pkg]
  (when-let [version ^Version (get (Version/identify) netty-pkg)]
    (.artifactVersion version)))

(def epoll?
  (memoize
   (fn epoll? []
     (when (version-of "netty-transport-native-epoll")
       ;; it might be explicitly turned off
       (eval `(io.netty.channel.epoll.Epoll/isAvailable))))))

(def kqueue?
  (memoize
   (fn epoll? []
     (when (version-of "netty-transport-native-kqueue")
       ;; it might be explicitly turned off
       (eval `(io.netty.channel.kqueue.KQueue/isAvailable))))))

(defn ^MultithreadEventLoopGroup event-loop
  [^long thread-count]
  (cond
    (epoll?)
      (eval `(io.netty.channel.epoll.EpollEventLoopGroup. ~thread-count))
    (kqueue?)
      (eval `(io.netty.channel.kqueue.KQueueEventLoopGroup. ~thread-count))
    :nio?
      (NioEventLoopGroup. thread-count)))

(defn ^Class socket-chan
  []
  (cond
    (epoll?)
      (eval `io.netty.channel.epoll.EpollServerSocketChannel)
    (kqueue?)
      (eval `io.netty.channel.kqueue.KQueueServerSocketChannel)
    :nio?
      NioServerSocketChannel))
