(ns iny.ring.handler
  (:require [clojure.tools.logging :as log]
            [iny.netty.handler :as handler]
            [iny.http.body :refer [release]])
  (:import [io.netty.channel
            ChannelInboundHandler]
           [iny.ring.request
            RingRequest]))

(defn ^ChannelInboundHandler handler
  [user-handler]
  (handler/inbound
    (exceptionCaught [_ ctx ex]
      (log/warn ex)
      (.close ctx (.voidPromise ctx)))
    (channelRead [_ ctx msg]
      (when (instance? RingRequest msg)
        (let [result (user-handler msg)]
          (.writeAndFlush ctx result (.voidPromise ctx))))
      (release msg))))
