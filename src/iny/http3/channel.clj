(ns iny.http3.channel
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
            ChannelInitializer]
           [io.netty.handler.ssl.util
            SelfSignedCertificate]
           [io.netty.incubator.channel.uring
            IOUringDatagramChannel]
           [io.netty.incubator.codec.http3
            Http3
            Http3FrameToHttpObjectCodec
            Http3ServerConnectionHandler]
           [io.netty.incubator.codec.quic
            InsecureQuicTokenHandler
            QuicChannel
            QuicServerCodecBuilder
            QuicSslContextBuilder
            QuicStreamChannel]))

(defn init-stream
  [worker-group user-handler]
  (proxy [ChannelInitializer] []
    (initChannel [^QuicStreamChannel ch]
      (let [pipeline (.pipeline ch)]
        (.addLast pipeline "http3-codec" (Http3FrameToHttpObjectCodec. true))
        (.addLast pipeline worker-group "ring-handler" user-handler)
        (.addBefore pipeline "ring-handler" "http-handler" (http-handler worker-group))))))

(defn init-connection
  [worker-group user-handler]
  (proxy [ChannelInitializer] []
    (initChannel [^QuicChannel ch]
      (.addLast
       (.pipeline ch)
       "http3-cxn-handler"
       (Http3ServerConnectionHandler.
        (init-stream worker-group user-handler))))))

(defn ^QuicServerCodecBuilder http3-builder
  [{:keys [worker-group user-handler ^SelfSignedCertificate cert]}]
  (let [ssl-context (-> (QuicSslContextBuilder/forServer
                         (.key cert) nil
                         ^"[Ljava.security.cert.X509Certificate;"
                         (into-array X509Certificate [(.cert cert)]))
                        (.applicationProtocols
                         (Http3/supportedApplicationProtocols))
                        (.build))]
    (log/info "HTTP3"
              (join ", " (seq (Http3/supportedApplicationProtocols)))
              "supported")
    (doto (Http3/newQuicServerCodecBuilder)
          (.sslContext ssl-context)
          (.maxIdleTimeout 5000 TimeUnit/MILLISECONDS)
          (.initialMaxData 8192)
          (.initialMaxStreamDataBidirectionalLocal 65536)
          (.initialMaxStreamDataBidirectionalRemote 65536)
          (.initialMaxStreamsBidirectional 50)
          (.tokenHandler InsecureQuicTokenHandler/INSTANCE)
          (.handler (init-connection worker-group user-handler)))))

(defn ^Bootstrap bootstrap
  [{:keys [parent-group] :as options}]
  (doto (Bootstrap.)
        (.group parent-group)
        (.channel IOUringDatagramChannel)
        (.handler (.build (http3-builder options)))))
