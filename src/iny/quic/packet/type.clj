(ns iny.quic.packet.type
  (:refer-clojure :exclude [name]))

(def initial 0x00)
(def retry 0x01)
(def handshake 0x02)
(def zero-rtt 0x03)

(defn name
  [type]
  (condp = type
    initial ::initial
    retry ::retry
    handshake ::handshake
    zero-rtt ::zero-rtt
    ::unknown))

(defn byte->type
  [^long type-byte]
  (-> type-byte
      (bit-and 0x30)
      (bit-shift-right 4)
      (name)))

(defn long?
  [^long type-byte]
  (> (bit-and type-byte 0x80)))
