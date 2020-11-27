(ns iny.quic.crypto.label
  (:refer-clojure :exclude [key]))

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

(def ^String iv
  "quic iv")

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
