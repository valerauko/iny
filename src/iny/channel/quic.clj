(ns iny.channel.quic
  (:require [clojure.tools.logging :as log]
            [clojure.string :refer [join]]
            [iny.native :as native]
            [iny.tls :refer [->ssl-opts ->ssl-context-builder]]
            [iny.http3.pipeline :as http3])
  (:import [java.util.concurrent
            TimeUnit]
           [java.security.cert
            X509Certificate]
           [io.netty.bootstrap
            Bootstrap]
           [io.netty.buffer
            PooledByteBufAllocator]
           [io.netty.channel
            ChannelOption]
           [io.netty.handler.ssl.util
            SelfSignedCertificate]
           [io.netty.incubator.channel.uring
            IOUringDatagramChannel]
           [io.netty.incubator.codec.http3
            Http3]
           [io.netty.incubator.codec.quic
            InsecureQuicTokenHandler
            QuicServerCodecBuilder
            QuicSslContextBuilder]))

(defn ^QuicServerCodecBuilder quic-builder
  [{:keys [worker-group user-handler ssl] :as options}]
  (let [ssl-context (-> ^QuicSslContextBuilder (->ssl-context-builder ssl :quic)
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
          (.handler (http3/init-connection worker-group user-handler)))))

(defn ^Bootstrap bootstrap
  [{:keys [parent-group] :as options}]
  (doto (Bootstrap.)
        (.option ChannelOption/SO_REUSEADDR true)
        (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
        (.option ChannelOption/ALLOCATOR (PooledByteBufAllocator. true))
        (.group parent-group)
        (.channel (native/datagram-chan))
        (.handler (.build (quic-builder options)))))
