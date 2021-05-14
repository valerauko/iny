(ns iny.http2.pipeline
  (:require [clojure.tools.logging :as log]
            [iny.netty.handler :as handler]
            [iny.http1.pipeline :as http1]
            [iny.http2.handler :refer [http2-handler]])
  (:import [iny
            AlpnNegotiator]
           [io.netty.util
            AsciiString]
           [io.netty.channel
            ChannelHandler
            ChannelHandlerContext
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
            Http2FrameCodec
            Http2SecurityUtil]
           [io.netty.handler.ssl
            ApplicationProtocolConfig
            ApplicationProtocolConfig$Protocol
            ApplicationProtocolConfig$SelectedListenerFailureBehavior
            ApplicationProtocolConfig$SelectorFailureBehavior
            ApplicationProtocolNames
            ApplicationProtocolNegotiationHandler
            OpenSsl
            SslContext
            SslContextBuilder
            SslProvider
            SupportedCipherSuiteFilter]
           [io.netty.handler.ssl.util
            SelfSignedCertificate]))

(defn ^ChannelHandler http-fallback
  []
  (handler/inbound
    (channelRead
     [this ctx msg]
     (let [pipeline (.pipeline ctx)]
       ;; removes the h2c-upgrade handler (no upgrade was attempted)
       (http1/server-pipeline pipeline)
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

(defn ^SslContext ssl-context
  [{:keys [cert private-key]}]
  (let [provider (if (OpenSsl/isAlpnSupported)
                   SslProvider/OPENSSL
                   SslProvider/JDK)]
    (-> (SslContextBuilder/forServer cert private-key)
        (.sslProvider provider)
        (.ciphers Http2SecurityUtil/CIPHERS SupportedCipherSuiteFilter/INSTANCE)
        (.applicationProtocolConfig
         (ApplicationProtocolConfig.
          ApplicationProtocolConfig$Protocol/ALPN
          ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
          ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
          ^Iterable [ApplicationProtocolNames/HTTP_2
                     ApplicationProtocolNames/HTTP_1_1]))
        (.build))))

(defn server-pipeline
  [^ChannelPipeline pipeline {:keys [^SelfSignedCertificate cert]}]
  (if cert
    (let [ctx (ssl-context {:cert (.certificate cert)
                            :private-key (.privateKey cert)})]
      (.addBefore pipeline "ring-handler" "ssl-handler"
                  (.newHandler ctx (.alloc (.channel pipeline))))
      (.addBefore pipeline "ring-handler" "alpn-negotiator" (AlpnNegotiator.))
      (.addBefore pipeline "ring-handler" "alpn-pipeline"
                  (handler/inbound
                   (channelRead [_ ctx msg]
                     (log/info (.pipeline ctx))
                     (.close ctx))
                   (userEventTriggered [this ctx evt]
                     (when (or (false? evt) (string? evt))
                       (.remove pipeline this)
                       (case evt
                         "h2"
                         (do
                           (.addBefore pipeline "ring-handler" "http2-codec"
                                       (http2-codec))
                           (.addBefore pipeline "ring-handler" "iny-http2-inbound"
                                       (http2-handler)))
                         "http/1.1"
                         (http1/server-pipeline pipeline)
                         ;; otherwise shutdown, can't deal with it
                         (.close ctx)))))))
    (do
      (.addBefore pipeline "ring-handler" "h2c-upgrade" (h2c-upgrade))
      (.addBefore pipeline "ring-handler" "http-fallback" (http-fallback)))))
