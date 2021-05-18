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
      (.close ctx))
    (channelRead [_ ctx msg]
      (when (map? msg)
        (let [stream (:iny.http2/stream msg)
              result (user-handler (dissoc msg :iny.http2/stream))]
          (.writeAndFlush ctx
                          (if stream
                            (assoc result :iny.http2/stream stream)
                            (dissoc result :iny.http2/stream))
                          (.voidPromise ctx))))
      (release msg))))
