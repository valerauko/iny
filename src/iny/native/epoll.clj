(ns iny.native.epoll
  (:import [io.netty.channel.epoll
            Epoll
            EpollEventLoopGroup
            EpollServerSocketChannel]))

(defmethod iny.native/available? :epoll
  [_]
  (Epoll/isAvailable))

(defmethod iny.native/event-loop :epoll
  [_ thread-count]
  (EpollEventLoopGroup. ^long thread-count))

(defmethod iny.native/socket-chan :epoll
  [_]
  EpollServerSocketChannel)
