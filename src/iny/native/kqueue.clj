(ns iny.native.kqueue
  (:import [java.util.concurrent
            ThreadFactory]
           [io.netty.channel.kqueue
            KQueue
            KQueueDatagramChannel
            KQueueEventLoopGroup
            KQueueServerSocketChannel]))

(defmethod iny.native/available? :kqueue
  [_]
  (KQueue/isAvailable))

(defmethod iny.native/event-loop :kqueue
  [_ thread-count factory]
  (KQueueEventLoopGroup. ^long thread-count ^ThreadFactory factory))

(defmethod iny.native/socket-chan :kqueue
  [_]
  KQueueServerSocketChannel)

(defmethod iny.native/datagram-chan :kqueue
  [_]
  KQueueDatagramChannel)
