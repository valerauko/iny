(ns iny.tls
  (:require [iny.interop :refer [->typed-array]])
  (:import [java.security
            PrivateKey]
           [java.security.cert
            X509Certificate]
           [io.netty.handler.ssl
            SslContextBuilder]
           [io.netty.handler.ssl.util
            SelfSignedCertificate]
           [io.netty.incubator.codec.quic
            QuicSslContextBuilder]))

(defprotocol Certify
  (->ssl-opts [_]))

(extend-protocol Certify
  SelfSignedCertificate
  (->ssl-opts
   [^SelfSignedCertificate cert]
   {:private-key (.key cert)
    :key-password nil
    :certificates [(.cert cert)]}))

;; the result still has to be hinted at the caller side,
;; but it's still better than having to import and hint all the other classes
(defn ->ssl-context-builder
  ([options] (->ssl-context-builder options false))
  ([options quic?]
   (let [^PrivateKey private-key (:private-key options)
         ^String pass (:key-password options)
         certs (->typed-array X509Certificate (:certificates options))]
     (if quic?
       (QuicSslContextBuilder/forServer private-key pass certs)
       (SslContextBuilder/forServer private-key pass certs)))))
