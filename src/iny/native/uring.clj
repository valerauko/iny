(ns iny.native.uring
  (:import [io.netty.incubator.channel.uring
            IOUring
            IOUringEventLoopGroup
            IOUringServerSocketChannel]))

(defmethod iny.native/available? :uring
  [_]
  (IOUring/isAvailable))

(defmethod iny.native/event-loop :uring
  [_ thread-count]
  (IOUringEventLoopGroup. ^long thread-count))

(defmethod iny.native/socket-chan :uring
  [_]
  IOUringServerSocketChannel)

; (io.netty.incubator.channel.uring.IOUring/unavailabilityCause)
