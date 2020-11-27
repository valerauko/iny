(ns iny.quic.var-int
  (:import [io.netty.buffer
            ByteBuf]))

(defn bytes->long
  [^bytes arr]
  (-> arr (bigint) (long)))

(defn var-int!
  [^ByteBuf buffer]
  (let [full-first-byte (.readByte buffer)
        length (bit-shift-left 1 (bit-shift-right
                                  (bit-and full-first-byte 0xc0)
                                  6))
        first-byte (bit-and full-first-byte
                            (- 0xff 0xc0))
        arr (byte-array length)]
    (aset-byte arr 0 first-byte)
    (.readBytes buffer arr 1 (dec length))
    (bytes->long arr)))
