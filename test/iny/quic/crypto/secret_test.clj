(ns iny.quic.crypto.secret-test
  (:require [clojure.test :refer :all]
            [iny.quic.crypto.level :as level]
            [iny.quic.crypto.secret :refer :all]
            [iny.quic.crypto.label :as label])
  (:import [io.netty.buffer
            ByteBufUtil]
           [java.security
            SecureRandom]
           [org.bouncycastle.tls
            HashAlgorithm]
           [org.bouncycastle.tls.crypto.impl.bc
            BcTlsCrypto
            BcTlsSecret]))

(defn dump
  [secret]
  (ByteBufUtil/hexDump (.extract secret)))

(defn secret-dump
  [secrets path]
  (dump (get-in secrets path)))

(deftest hkdf-test
  ;; these are only to make sure i'm using the tools correctly
  ;; cases from the hkdf rfc: https://tools.ietf.org/html/rfc5869#appendix-A
  (let [random (SecureRandom.)
        crypto (BcTlsCrypto. random)
        inner-secret #(BcTlsSecret. crypto
                                    (ByteBufUtil/decodeHexDump
                                     "000102030405060708090a0b0c"))
        salt (ByteBufUtil/decodeHexDump
              "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        initial-secret #(initial (inner-secret) salt)]
    (testing "HKDF-Extract"
      (let [subject (initial-secret)]
        (is (= (dump subject)
               (str "077709362c2e32df0ddc3f0dc47bba63"
                    "90b6c73bb50f9c3122ec844ad7c2b3e5")))))
    (testing "HKDF-Expand"
      (let [subject (.hkdfExpand (initial-secret)
                                 HashAlgorithm/sha256
                                 (ByteBufUtil/decodeHexDump
                                  "f0f1f2f3f4f5f6f7f8f9")
                                 42)]
        (is (= (dump subject)
               (str "3cb25f25faacd57a90434f64d036"
                    "2f2a2d2d0a90cf1a5a4c5db02d56"
                    "ecc4c5bf34007208d5b887185865")))))))

;; values from draft-29 (care: salts change by draft)
;; https://tools.ietf.org/html/draft-ietf-quic-tls-29#appendix-A.1
(deftest initial-secrets-test
  (let [conn-id (ByteBufUtil/decodeHexDump "8394c8f03e515708")
        common #(initial (tls-secret ::level/initial) conn-id)]
    (testing "Common initial secret"
      (is (= (dump (common))
             (str "1e7e7764529715b1e0ddc8e9753c6157"
                  "6769605187793ed366f8bbf8c9e986eb"))))
    ;; these are also covered by label-test
    (testing "Initial client secret"
      (let [subject (label/expand (common) label/client-initial 32)]
        (is (= (dump subject)
               (str "0088119288f1d866733ceeed15ff9d50"
                    "902cf82952eee27e9d4d4918ea371d87")))))
    (testing "Initial server secret"
      (let [subject (label/expand (common) label/server-initial 32)]
        (is (= (dump subject)
               (str "006f881359244dd9ad1acf85f595bad6"
                    "7c13f9f5586f5e64e1acae1d9ea8f616")))))))

(deftest initial-keys-test
  (let [conn-id (ByteBufUtil/decodeHexDump "8394c8f03e515708")
        secrets (initial-secrets conn-id)]
    (testing "Server secrets"
      (is (= (secret-dump secrets [:server :key])
             "149d0b1662ab871fbe63c49b5e655a5d"))
      (is (= (secret-dump secrets [:server :iv])
             "bab2b12a4c76016ace47856d"))
      (is (= (secret-dump secrets [:server :pn])
             "c0c499a65a60024a18a250974ea01dfa")))
    (testing "Client secrets"
      (is (= (secret-dump secrets [:client :key])
             "175257a31eb09dea9366d8bb79ad80ba"))
      (is (= (secret-dump secrets [:client :iv])
             "6b26114b9cba2b63a9e8dd4f"))
      (is (= (secret-dump secrets [:client :pn])
             "9ddd12c994c0698b89374a9c077a3077")))))
