(ns iny.native
  (:require [clojure.tools.logging :as log])
  (:import [io.netty.util
            Version]
           [io.netty.util.internal
            PlatformDependent]
           [io.netty.channel
            MultithreadEventLoopGroup]
           [io.netty.channel.nio
            NioEventLoopGroup]
           [io.netty.channel.socket.nio
            NioDatagramChannel
            NioServerSocketChannel]))

(def packs
  [(when (resolve 'io.netty.incubator.channel.uring.IOUring)
     :uring)
   (when (resolve 'io.netty.channel.epoll.Epoll)
     :epoll)
   (when (resolve 'io.netty.channel.kqueue.KQueue)
     :kqueue)
   :nio])

(defmulti available?
  (fn available-dispatch [pack]
    (some #{pack} packs)))

(defmulti ^MultithreadEventLoopGroup event-loop
  (fn event-dispatch
    ([_] (event-dispatch nil nil))
    ([pack _] (some #{pack} packs))))

(defmulti ^Class socket-chan
  (fn socket-dispatch
    ([] (socket-dispatch nil))
    ([pack] (some #{pack} packs))))

(defmulti ^Class datagram-chan
  (fn datagram-dispatch
    ([] (datagram-dispatch nil))
    ([pack] (some #{pack} packs))))

(mapv
  (fn loader [pack]
    (require (symbol (str "iny.native." (name pack))) :reload)
    (log/info "Loaded" (name pack) "support"))
  (filter #(and % (not= % :nio)) packs))

(defmethod available? :nio
  [_]
  true)

(defmethod available? :default
  [_]
  false)

(defn epoll?
  []
  (available? :epoll))

(defn kqueue?
  []
  (available? :kqueue))

(defn uring?
  []
  (available? :uring))

(defn wanted
  []
  (first (filter available? packs)))

(defmethod event-loop :nio
  [_ thread-count]
  (NioEventLoopGroup. ^long thread-count))

(defmethod event-loop :default
  [thread-count]
  (event-loop (wanted) thread-count))

(defmethod socket-chan :nio
  [_]
  NioServerSocketChannel)

(defmethod socket-chan :default
  []
  (socket-chan (wanted)))

(defmethod datagram-chan :nio
  [_]
  NioDatagramChannel)

(defmethod datagram-chan :default
  []
  (socket-chan (wanted)))

(defn version-of
  [netty-pkg]
  (when-let [version ^Version (get (Version/identify) netty-pkg)]
    (.artifactVersion version)))

(defn suggest-package
  ([]
   (let [os (PlatformDependent/normalizedOs)
         arch (PlatformDependent/normalizedArch)
         clsfn #(when (% arch) (str os "-" arch))]
     (case os
       "linux"
       (when-not (epoll?)
         (let [classifier (clsfn #{"x86_64" "aarch64"})]
           (suggest-package "epoll" classifier)))
       ("darwin" "mac")
       (when-not (kqueue?)
         (let [classifier (clsfn #{"x86_64"})]
           (suggest-package "kqueue" classifier)))
       ("bsd")
       (when-not (kqueue?)
         (suggest-package "kqueue" nil))
       nil)))
  ([name classifier]
   (let [netty-version (version-of "netty-transport")]
     (log/debug
      (str "Your system likely supports '" name "'. "
           "For better native performance, try adding "
           "`[io.netty/netty-transport-native-" name " \"" netty-version "\""
           (when classifier (str " :classifier \"" classifier "\""))
           "]` to your dependencies.")))))

(when-not (some #{:epoll :kqueue} packs)
  (suggest-package))
