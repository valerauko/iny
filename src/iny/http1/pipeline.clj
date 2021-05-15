(ns iny.http1.pipeline
  (:require [iny.http1.handler :refer [http-handler]])
  (:import [io.netty.channel
            ChannelPipeline]
           [io.netty.handler.codec.http
            HttpRequestDecoder
            HttpResponseEncoder
            HttpServerCodec
            HttpServerExpectContinueHandler]))

(defn server-pipeline
  [^ChannelPipeline pipeline]
  (.addBefore pipeline "ring-handler" "http1-inbound" (HttpRequestDecoder. 4096 8192 65536))
  (let [ring-executor (-> pipeline (.context "ring-handler") (.executor))]
    (.addBefore pipeline ring-executor "ring-handler" "http1-outbound"
                (HttpResponseEncoder.)))
  (.addBefore pipeline "ring-handler" "continue" (HttpServerExpectContinueHandler.))
  (.addBefore pipeline "ring-handler" "iny-http1-inbound" (http-handler))
  ;; called from here and there, might not have codec in pipeline
  (when (.get pipeline HttpServerCodec) (.remove pipeline HttpServerCodec)))
