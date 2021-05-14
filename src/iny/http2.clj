(ns iny.http2
  (:require [clojure.tools.logging :as log]
            [iny.netty.handler :as handler]
            [iny.http1 :as http1]
            [iny.http2.handler :refer [http2-handler]])
  (:import [io.netty.util
            AsciiString]
           [io.netty.channel
            ChannelHandler
            ChannelPipeline]
           [io.netty.handler.codec.http
            HttpServerCodec
            HttpServerUpgradeHandler
            HttpServerUpgradeHandler$UpgradeCodecFactory]
           [io.netty.handler.codec.http2
            Http2FrameCodecBuilder
            Http2CodecUtil
            Http2MultiplexHandler
            CleartextHttp2ServerUpgradeHandler
            Http2ServerUpgradeCodec
            Http2FrameCodec]))

(defn ^ChannelHandler http-fallback
  [build-http-pipeline]
  (handler/inbound
    (channelRead
     [this ctx msg]
     (let [pipeline (.pipeline ctx)]
       ;; removes the h2c-upgrade handler (no upgrade was attempted)
       (build-http-pipeline pipeline)
       (.fireChannelRead ctx msg)
       (.remove pipeline HttpServerUpgradeHandler)
       (.remove pipeline this)))))

(let [builder (Http2FrameCodecBuilder/forServer)]
  (defn ^Http2FrameCodec http2-codec
    []
    (.build builder)))

(defn upgrade-factory
  [^Http2FrameCodec codec http2-handler]
  (reify
    HttpServerUpgradeHandler$UpgradeCodecFactory
    (newUpgradeCodec [_ proto]
      (case proto
        "h2c"
        (Http2ServerUpgradeCodec.
         codec
         ^"[Lio.netty.channel.ChannelHandler;"
         (into-array ChannelHandler [http2-handler]))))))

(defn ^CleartextHttp2ServerUpgradeHandler h2c-upgrade
  []
  (let [source-codec (HttpServerCodec. 4096 8192 65536)
        handler (http2-handler)
        codec (http2-codec)
        factory (upgrade-factory codec handler)]
    (CleartextHttp2ServerUpgradeHandler.
     source-codec
     (HttpServerUpgradeHandler. source-codec factory)
     (handler/inbound
      (handlerAdded [this ctx]
        (let [pipeline (.pipeline ctx)]
          (.remove pipeline "http-fallback")
          (.addAfter pipeline (.name ctx) "iny-http2-inbound" handler)
          (.addAfter pipeline (.name ctx) "http2-codec" codec)
          (.remove pipeline this)))))))

(defn server-pipeline
  [^ChannelPipeline pipeline]
  (.addBefore pipeline "ring-handler" "h2c-upgrade" (h2c-upgrade))
  (.addBefore pipeline "ring-handler" "http-fallback"
              (http-fallback http1/server-pipeline)))
