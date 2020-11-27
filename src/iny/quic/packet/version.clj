(ns iny.quic.packet.version
  (:refer-clojure :exclude [name]))

(def tls 0x51474fff)
(def draft-29 0xff00001d)

(defn name
  [version-byte]
  (condp = version-byte
    tls ::tls
    draft-29 ::draft-29
    ::unknown))
