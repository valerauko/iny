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

(defn suggest-package
  ([]
   (let [os (PlatformDependent/normalizedOs)
         arch (PlatformDependent/normalizedArch)
         clsfn #(when (% arch) (str os "-" arch))]
     (case os
       "linux"
       (when-not (epoll?)
         (let [classifier (clsfn #{"x86_64" "aarch64"})]
           (suggest-package "epoll" classifier)))
       ("darwin" "mac")
       (when-not (kqueue?)
         (let [classifier (clsfn #{"x86_64"})]
           (suggest-package "kqueue" classifier)))
       ("bsd")
       (when-not (kqueue?)
         (suggest-package "kqueue" nil))
       nil)))
  ([name classifier]
   (let [netty-version (version-of "netty-transport")]
     (log/debug
      (str "Your system likely supports '" name "'. "
           "For better native performance, try adding "
           "`[io.netty/netty-transport-native-" name " \"" netty-version "\""
           (when classifier (str " :classifier \"" classifier "\""))
           "]` to your dependencies.")))))

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
  (when-not (or (epoll?) (kqueue?))
    (suggest-package))
  (cond
    (epoll?)
      (eval `io.netty.channel.epoll.EpollServerSocketChannel)
    (kqueue?)
      (eval `io.netty.channel.kqueue.KQueueServerSocketChannel)
    :nio?
      NioServerSocketChannel))
