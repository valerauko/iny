(ns iny.tls
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

(defrecord SslOpts
  [^PrivateKey private-key
   ^String key-password
   ^"[Ljava.security.cert.X509Certificate;" certificates])

(defn ^"[Ljava.security.cert.X509Certificate;" ->cert-array
  [thing]
  (into-array X509Certificate
              (if (or (seq? thing) (set? thing) (vector? thing))
                thing
                [thing])))

(extend-protocol Certify
  SelfSignedCertificate
  (->ssl-opts
   [^SelfSignedCertificate cert]
   (->SslOpts (.key cert) nil (->cert-array (.cert cert)))))

;; the result still has to be hinted at the caller side,
;; but it's still better than having to import and hint all the other classes
(defn ->ssl-context-builder
  ([options] (->ssl-context-builder options false))
  ([options quic?]
   (let [^PrivateKey private-key (:private-key options)
         ^String pass (:key-password options)
         ^"[Ljava.security.cert.X509Certificate;" certs (:certificates options)]
     (if quic?
       (QuicSslContextBuilder/forServer private-key pass certs)
       (SslContextBuilder/forServer private-key pass certs)))))
