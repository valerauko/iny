(ns iny.native.kqueue
  (:import [io.netty.channel.kqueue
            KQueue
            KQueueEventLoopGroup
            KQueueServerSocketChannel]))

(defmethod iny.native/available? :kqueue
  [_]
  (KQueue/isAvailable))

(defmethod iny.native/event-loop :kqueue
  [_ thread-count]
  (KQueueEventLoopGroup. ^long thread-count))

(defmethod iny.native/socket-chan :kqueue
  [_]
  KQueueServerSocketChannel)
