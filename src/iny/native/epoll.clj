(ns iny.native.epoll
  (:import [java.util.concurrent
            ThreadFactory]
           [io.netty.channel.epoll
            Epoll
            EpollDatagramChannel
            EpollEventLoopGroup
            EpollServerSocketChannel]))

(defmethod iny.native/available? :epoll
  [_]
  (Epoll/isAvailable))

(defmethod iny.native/event-loop :epoll
  [_ thread-count factory]
  (EpollEventLoopGroup. ^long thread-count ^ThreadFactory factory))

(defmethod iny.native/socket-chan :epoll
  [_]
  EpollServerSocketChannel)

(defmethod iny.native/datagram-chan :epoll
  [_]
  EpollDatagramChannel)
