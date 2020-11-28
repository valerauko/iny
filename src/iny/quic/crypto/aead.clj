(ns iny.quic.crypto.aead
  (:import [javax.crypto
            Cipher
            SecretKey]
           [javax.crypto.spec
            GCMParameterSpec
            SecretKeySpec]))

;; https://tools.ietf.org/html/draft-ietf-quic-tls-29#section-5.4.1
(defn ^"[B" process-headers
  ;; default to long header
  ([^bytes sample ^bytes subject ^bytes pn-key]
   (process-headers sample subject pn-key false))
  ([^bytes sample ^bytes subject ^bytes pn-key short?]
   (let [secret-key (SecretKeySpec. pn-key 0 (alength pn-key) "AES")
         ;; TODO: make cipher a threadlocal
         cipher (doto (Cipher/getInstance "AES/ECB/NoPadding" "SunJCE")
                      (.init Cipher/ENCRYPT_MODE secret-key))
         mask (.doFinal cipher sample)
         ;; long header: 4 bits masked, short header: 5 bits masked
         first-mask (if short? 0x1f 0xf)]
     (areduce subject i aggr (byte-array (alength subject))
              (let [mask-byte (aget mask i)
                    byte-mask (if (zero? i)
                                (bit-and first-mask mask-byte)
                                mask-byte)
                    subject-byte (aget subject i)
                    masked-byte (bit-xor subject-byte
                                         ^long byte-mask)]
                (aset-byte aggr i masked-byte)
                aggr)))))

(defn ^"[B" generate-nonce
  [^bytes init-vec ^bytes packet-number]
  (let [iv-len (alength init-vec)
        nonce (byte-array iv-len)]
    (System/arraycopy packet-number 0
                      nonce (- iv-len 4) 4)
    (doseq [i (range iv-len)]
      (aset-byte nonce i (bit-xor (aget nonce i) (aget init-vec i))))
    nonce))

(defn ^"[B" process
  [^bytes source ^bytes packet-number ^bytes aad ^bytes key ^bytes iv mode]
  (let [secret-key (SecretKeySpec. key 0 (alength key) "AES")
        nonce (generate-nonce iv packet-number)
        spec (GCMParameterSpec. 128 nonce)
        cipher (doto (Cipher/getInstance "AES/GCM/NoPadding" "SunJCE")
                     (.init ^int mode secret-key spec))]
    (.updateAAD cipher aad)
    (.doFinal cipher source)))

(defn ^"[B" open
  [source packet-number aad key iv]
  (process source packet-number aad key iv Cipher/DECRYPT_MODE))

(defn ^"[B" seal
  [source packet-number aad key iv]
  (process source packet-number aad key iv Cipher/ENCRYPT_MODE))
