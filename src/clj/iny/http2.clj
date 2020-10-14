(ns iny.http2
  (:require [clojure.tools.logging :as log]
            [iny.http2.handler :refer [http2-handler]])
  (:import [iny.http2
            HandlerBuilder]
           [io.netty.handler.codec.http
            HttpServerCodec
            HttpServerUpgradeHandler
            HttpServerUpgradeHandler$UpgradeCodecFactory]
           [io.netty.handler.codec.http2
            DefaultHttp2Connection
            CleartextHttp2ServerUpgradeHandler
            Http2ServerUpgradeCodec
            AbstractHttp2ConnectionHandlerBuilder
            Http2ConnectionHandlerBuilder
            Http2ConnectionHandler
            Http2FrameListener]))

(def handler-builder
  (HandlerBuilder. http2-handler))

(def upgrade-factory
  (reify
    HttpServerUpgradeHandler$UpgradeCodecFactory
    (newUpgradeCodec [_ proto]
      (log/info (str "newUpgradeCodec " proto))
      (let [handler (.build handler-builder)]
        (Http2ServerUpgradeCodec. "http2-handler" handler)))))

(defn upgrade-handler
  [source-codec]
  (HttpServerUpgradeHandler. source-codec upgrade-factory))

(defn h2c-upgrade []
  (let [source-codec (HttpServerCodec.)
        upgrade-handler (upgrade-handler source-codec)
        builder handler-builder
        handler (.build builder)]
    (CleartextHttp2ServerUpgradeHandler.
     source-codec
     upgrade-handler
     handler)))
