(ns iny.http1
  (:require [iny.http1.handler :refer [http-handler]])
  (:import [io.netty.channel
            ChannelPipeline]
           [io.netty.handler.codec.http
            HttpServerExpectContinueHandler]))

(defn server-pipeline
  [^ChannelPipeline pipeline user-handler]
  (.addLast pipeline "continue" (HttpServerExpectContinueHandler.))
  (.addLast pipeline "user-handler" (http-handler user-handler)))
