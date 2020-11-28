(ns iny.quic.crypto.secret
  (:require [iny.quic.crypto.level :as level]
            [iny.quic.crypto.label :as label])
  (:import [java.security
            SecureRandom]
           [io.netty.buffer
            ByteBufUtil]
           [org.bouncycastle.tls
            HashAlgorithm]
           [org.bouncycastle.tls.crypto.impl.bc
            BcTlsCrypto
            BcTlsSecret]))

(def salt
  {::level/initial
   (ByteBufUtil/decodeHexDump "afbfec289993d24c9e9786f19c6111e04390a899")})

(defn ^BcTlsSecret tls-secret
  [level]
  (let [random (SecureRandom.)
        crypto (BcTlsCrypto. random)]
    (BcTlsSecret. crypto (salt level))))

(defn initial
  [^BcTlsSecret tls-secret ^bytes connection-id]
  (.hkdfExtract tls-secret HashAlgorithm/sha256 connection-id))

(defn initial-secrets
  [^bytes connection-id]
  (let [hkdf (tls-secret ::level/initial)
        initial-secret (initial hkdf connection-id)
        client-secret (label/expand initial-secret label/client-initial 32)
        server-secret (label/expand initial-secret label/server-initial 32)]
    {:server {:key (label/expand server-secret label/key 16)
              :iv (label/expand server-secret label/iv 12)
              :pn (label/expand server-secret label/hp 16)}
     :client {:key (label/expand client-secret label/key 16)
              :iv (label/expand client-secret label/iv 12)
              :pn (label/expand client-secret label/hp 16)}}))
