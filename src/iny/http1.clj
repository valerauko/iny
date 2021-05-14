(ns iny.http1
  (:require [iny.http1.handler :refer [http-handler]])
  (:import [io.netty.channel
            ChannelPipeline]
           [io.netty.handler.codec.http
            HttpServerExpectContinueHandler]))

(defn server-pipeline
  [^ChannelPipeline pipeline executor]
  (.addBefore pipeline "ring-handler" "continue" (HttpServerExpectContinueHandler.))
  (.addBefore pipeline "ring-handler" "iny-http1-inbound" (http-handler executor)))
