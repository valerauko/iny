(ns iny.quic.crypto.aead-test
  (:require [clojure.test :refer :all]
            [iny.quic.crypto.aead :refer :all]
            [iny.quic.crypto.secret :as secret])
  (:import [java.security
            SecureRandom]
           [io.netty.buffer
            ByteBufUtil]))

(deftest initial-aead-headers-test
  ;; values from https://tools.ietf.org/html/draft-ietf-quic-tls-29#appendix-A.2
  (let [conn-id (ByteBufUtil/decodeHexDump
                 "8394c8f03e515708")
        keys (secret/initial-secrets conn-id)
        client-key (.extract (get-in keys [:client :pn]))
        server-key (.extract (get-in keys [:server :pn]))]
    (let [sample (ByteBufUtil/decodeHexDump
                  "fb66bc5f93032b7ddd89fe0ff15d9c4f")
          header (ByteBufUtil/decodeHexDump "c300000002")
          expected "c54a95245b"]
      (testing "Client encrypt"
        (let [encrypted (process-headers sample header client-key)]
          (is (= (ByteBufUtil/hexDump encrypted)
                 expected))))
      (testing "Server decrypt"
        (let [encrypted (ByteBufUtil/decodeHexDump expected)
              decrypted (process-headers sample encrypted client-key)]
          (is (= (ByteBufUtil/hexDump decrypted)
                 "c300000002")))))
    (let [sample (ByteBufUtil/decodeHexDump
                  "823a5d3a1207c86ee49132824f046524")
          header (ByteBufUtil/decodeHexDump "c10001")
          expected "caaaf2"]
      (testing "Server encrypt"
        (let [encrypted (process-headers sample header server-key)]
          (is (= (ByteBufUtil/hexDump encrypted)
                 expected))))
      (testing "Client decrypt"
        (let [encrypted (ByteBufUtil/decodeHexDump expected)
              decrypted (process-headers sample encrypted server-key)]
          (is (= (ByteBufUtil/hexDump decrypted)
                 "c10001")))))))

(deftest initial-aead-seal-open
  (let [conn-id (byte-array [0x12 0x34 0x56 0x78 0x90 0xab 0xcd 0xef])
        keys (secret/initial-secrets conn-id)
        message (.getBytes "as gregor samsa awoke one morning")
        aad (.getBytes "aad")]
    (let [iv (.extract (get-in keys [:client :iv]))
          key (.extract (get-in keys [:client :key]))
          packet-no (byte-array [0x00 0x00 0x00 0x2a])
          encrypted (str "3bb69a220b556138dbf92c318f05af95"
                         "f8cf3805c4a5cafa6b9b97cd96b5e73b"
                         "8d4da7d7fccd645551c80bb1a79567fbfb")]
      (testing "Client seal"
        (let [result (seal message packet-no aad key iv)]
          (is (= (ByteBufUtil/hexDump result) encrypted))))
      (testing "Server open"
        (let [result (open (ByteBufUtil/decodeHexDump encrypted)
                           packet-no aad key iv)]
          (is (= (String. message) (String. result))))))))
