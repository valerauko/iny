(ns iny.quic.crypto.aead-test
  (:require [clojure.test :refer :all]
            [iny.quic.crypto.aead :refer :all]
            [iny.quic.crypto.secret :as secret])
  (:import [java.security
            SecureRandom]
           [io.netty.buffer
            ByteBufUtil]))

(deftest initial-aead-test
  ;; values from https://tools.ietf.org/html/draft-ietf-quic-tls-29#appendix-A.2
  (let [conn-id (ByteBufUtil/decodeHexDump
                 "8394c8f03e515708")
        keys (secret/initial-secrets conn-id)
        client-key (.extract (get-in keys [:client :pn]))
        server-key (.extract (get-in keys [:server :pn]))
        sample (ByteBufUtil/decodeHexDump
                "fb66bc5f93032b7ddd89fe0ff15d9c4f")
        header (ByteBufUtil/decodeHexDump "c300000002")]
    (testing "Client encrypt"
      (let [encrypted (process-headers sample header client-key)]
        (is (= (aget encrypted 0)
               (byte 0xc5)))
        (is (= (ByteBufUtil/hexDump (byte-array (rest encrypted)))
               (ByteBufUtil/hexDump (byte-array [0x4a 0x95 0x24 0x5b]))))))))
