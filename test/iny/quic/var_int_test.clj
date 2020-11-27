(ns iny.quic.var-int-test
  (:require [clojure.test :refer :all]
            [iny.quic.var-int :refer :all])
  (:import [io.netty.buffer
            Unpooled]))

(deftest var-int!-test
  (testing "8 bytes"
    (let [arr (byte-array [0xc2 0x19 0x7c 0x5e 0xff 0x14 0xe8 0x8c])
          buf (Unpooled/wrappedBuffer arr)]
      (is (= (var-int! buf) 151288809941952652))
      (is (not (.isReadable buf)))
      (.release buf)))
  (testing "4 bytes"
    (let [arr (byte-array [0x9d 0x7f 0x3e 0x7d])
          buf (Unpooled/wrappedBuffer arr)]
      (is (= (var-int! buf) 494878333))
      (is (not (.isReadable buf)))
      (.release buf)))
  (testing "2 bytes"
    (let [arr (byte-array [0x7b 0xbd])
          buf (Unpooled/wrappedBuffer arr)]
      (is (= (var-int! buf) 15293))
      (is (not (.isReadable buf)))
      (.release buf))))
