(ns iny.http3
  (:require [clojure.tools.logging :as log]
            [clojure.string :refer [join]]
            [iny.netty.handler :as handler]
            [iny.http1.handler :refer [http-handler]]
            [iny.ring.handler :as ring])
  (:import [java.util.concurrent
            TimeUnit]
           [java.security.cert
            X509Certificate]
           [io.netty.bootstrap
            Bootstrap]
           [io.netty.channel
            Channel
            ChannelFutureListener
            ChannelInitializer]
           [io.netty.incubator.channel.uring
            IOUringDatagramChannel]
           [io.netty.handler.ssl.util
            SelfSignedCertificate]
           [io.netty.incubator.codec.http3
            Http3
            Http3FrameToHttpObjectCodec
            Http3ServerConnectionHandler
            Http3HeadersFrame
            DefaultHttp3HeadersFrame
            DefaultHttp3DataFrame]
           [io.netty.incubator.codec.quic
            InsecureQuicTokenHandler
            QuicChannel
            QuicServerCodecBuilder
            QuicSslContextBuilder
            QuicStreamChannel]))

(defn init-stream
  [user-executor user-handler]
  (proxy [ChannelInitializer] []
    (initChannel [^QuicStreamChannel ch]
      (let [pipeline (.pipeline ch)]
        (.addLast pipeline "http3-codec" (Http3FrameToHttpObjectCodec. true))
        (.addLast pipeline user-executor "ring-handler" (ring/handler user-handler))
        (.addBefore pipeline "ring-handler" "http-handler" (http-handler user-executor))))))

(defn init-connection
  [user-executor user-handler]
  (proxy [ChannelInitializer] []
    (initChannel [^QuicChannel ch]
      (.addLast
       (.pipeline ch)
       "http3-connection"
       (Http3ServerConnectionHandler. (init-stream user-executor user-handler))))))

(defn ^QuicServerCodecBuilder http3-builder
  [user-executor user-handler]
  (let [cert (SelfSignedCertificate.)
        ssl-context (-> (QuicSslContextBuilder/forServer
                         (.key cert) nil
                         ^"[Ljava.security.cert.X509Certificate;"
                         (into-array X509Certificate [(.cert cert)]))
                        (.applicationProtocols
                         (Http3/supportedApplicationProtocols))
                        (.build))]
    (log/info "HTTP3" (join ", " (seq (Http3/supportedApplicationProtocols))) "supported")
    (doto (Http3/newQuicServerCodecBuilder)
          (.sslContext ssl-context)
          (.maxIdleTimeout 5000 TimeUnit/MILLISECONDS)
          (.initialMaxData 16383)
          (.initialMaxStreamDataBidirectionalLocal 16383)
          (.initialMaxStreamDataBidirectionalRemote 16383)
          (.initialMaxStreamsBidirectional 50)
          (.tokenHandler InsecureQuicTokenHandler/INSTANCE)
          (.handler (init-connection user-executor user-handler)))))

(defn ^Bootstrap http3-boot
  [port parent-group user-executor user-handler]
  (doto (Bootstrap.)
        (.group parent-group)
        (.channel IOUringDatagramChannel)
        (.handler (.build (http3-builder user-executor user-handler)))))
