(ns iny.ring.handler
  (:require [clojure.tools.logging :as log]
            [iny.http.body :refer [release]])
  (:import [io.netty.channel
            ChannelInboundHandler]
           [iny.ring.request
            RingRequest]))

(defn ^ChannelInboundHandler handler
  [user-handler]
  (reify
    ChannelInboundHandler

    (handlerAdded [_ ctx])
    (handlerRemoved [_ ctx])
    (exceptionCaught [_ ctx ex]
      (.fireExceptionCaught ctx ex))
    (channelRegistered [_ ctx])
    (channelUnregistered [_ ctx])
    (channelActive [_ ctx])
    (channelInactive [_ ctx])
    (channelRead [_ ctx msg]
      (when (instance? RingRequest msg)
        (let [result (user-handler msg)]
          (.writeAndFlush ctx result (.voidPromise ctx))
          (log/debug "sent off ring response")))
      (release msg))
    (channelReadComplete [_ ctx])
    (userEventTriggered [_ ctx event])
    (channelWritabilityChanged [_ ctx])))
