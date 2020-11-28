(ns iny.quic.crypto.label
  (:refer-clojure :exclude [key])
  (:import [java.nio.charset
            StandardCharsets]
           [io.netty.buffer
            Unpooled]
           [org.bouncycastle.tls
            HashAlgorithm]
           [org.bouncycastle.tls.crypto
            TlsSecret]))

(def ^String prefix
  "tls13 ")

(def ^String derived
  "derived")

(def ^String finished
  "finished")

(def ^String client-initial
  "client in")

(def ^String server-initial
  "server in")

(def ^String key
  "quic key")

;; iv is short for "initialization vector"
(def ^String iv
  "quic iv")

;; hp is short for "header protection"
(def ^String hp
  "quic hp")

(def ^String client-handshake-traffic-secret
  "c hs traffic")

(def ^String server-handshake-traffic-secret
  "s hs traffic")

(def ^String client-app-traffic-secret
  "c ap traffic")

(def ^String server-app-traffic-secret
  "s ap traffic")

;; HkdfLabel from tls1.3
;; https://www.rfc-editor.org/rfc/rfc8446.html#section-7.1
;; struct {
;;     uint16 length = Length;
;;     opaque label<7..255> = "tls13 " + Label;
;;     opaque context<0..255> = Context;
;; } HkdfLabel;
(defn ^"[B" ->hkdf-label
  [^String str-label ^bytes context ^long length]
  (let [prefixed-bytes (.getBytes (.concat prefix str-label)
                                  StandardCharsets/US_ASCII)
        label-length (alength prefixed-bytes)
        ctx-length (alength context)
        ;; 4 = length as short (2b) + label-length (1b) + ctx-length (1b)
        alloc-length (+ 4 label-length ctx-length)
        buffer (Unpooled/buffer alloc-length)
        result (byte-array alloc-length)]
    (doto buffer
          (.writeShort length)
          (.writeByte label-length)
          (.writeBytes prefixed-bytes)
          (.writeByte ctx-length)
          (.writeBytes context)
          (.readBytes result) ;; transfers contents to the byte-array
          (.release))
    result))

;; hkdf-expand-label from tls1.3
;; https://www.rfc-editor.org/rfc/rfc8446.html#section-7.1
;; HKDF-Expand-Label(Secret, Label, Context, Length) =
;;      HKDF-Expand(Secret, HkdfLabel, Length)
(defn expand
  ([secret label length]
   (expand secret label (byte-array 0) length))
  ([^TlsSecret secret label context ^long length]
   (let [hkdf-label (->hkdf-label label context length)]
     (.hkdfExpand secret HashAlgorithm/sha256 hkdf-label length))))
