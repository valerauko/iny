(ns iny.native.uring
  (:import [java.util.concurrent
            ThreadFactory]
           [io.netty.incubator.channel.uring
            IOUring
            IOUringDatagramChannel
            IOUringEventLoopGroup
            IOUringServerSocketChannel]))

(defmethod iny.native/available? :uring
  [_]
  (IOUring/isAvailable))

(defmethod iny.native/event-loop :uring
  [_ thread-count factory]
  (IOUringEventLoopGroup. ^long thread-count ^ThreadFactory factory))

(defmethod iny.native/socket-chan :uring
  [_]
  IOUringServerSocketChannel)

(defmethod iny.native/datagram-chan :uring
  [_]
  IOUringDatagramChannel)
