(ns iny.server
  (:require [clojure.tools.logging :as log]
            [iny.native :refer [event-loop] :as native]
            [iny.channel.tcp :as http]
            [iny.channel.quic :as http3]
            [iny.ring.handler :as ring])
  (:import [java.io
            Closeable]
           [java.lang
            AutoCloseable]
           [java.util.concurrent
            TimeUnit]
           [io.netty.bootstrap
            Bootstrap]
           [io.netty.channel
            Channel]
           [io.netty.util
            ResourceLeakDetector
            ResourceLeakDetector$Level]
           [io.netty.util.concurrent
            EventExecutorGroup]))

(ResourceLeakDetector/setLevel ResourceLeakDetector$Level/DISABLED)

(defn thread-counts
  []
  (let [total (- (* 2 (.availableProcessors (Runtime/getRuntime))) 3)
        io-parent (inc (int (Math/floor (/ total 3.0))))
        child-total (- (+ 2 total) io-parent)]
    {:parent io-parent
     :child (int (Math/floor (/ child-total 2)))
     :worker (int (Math/ceil (/ child-total 2)))}))

(defn shutdown-gracefully
  [^EventExecutorGroup executor]
  (.shutdownGracefully executor 10 100 TimeUnit/MILLISECONDS))

(defn ^Channel channel-of
  [^Bootstrap boot ^long port]
  (-> boot (.bind port) .sync .channel))

(defn ^Closeable server
  [handler & {:keys [port http3]
              :or {port 8080
                   http2 true
                   http3 false}
              :as options}]
  (let [{:keys [parent child worker] :as threads} (thread-counts)
        parent-group (event-loop parent :parent)
        child-group (event-loop child :child)
        worker-group (event-loop worker :worker)
        handler-options (merge
                         options
                         {:parent-group parent-group
                          :child-group child-group
                          :worker-group worker-group
                          :user-handler (ring/handler handler)})]
    (log/info "Starting Iny server at port" port)
    (try
      (let [tcp-channel (channel-of (http/bootstrap handler-options) port)
            udp-channel (when http3
                          (channel-of (http3/bootstrap handler-options) port))
            closed? (atom false)]
        (reify
          Object
          (toString [_]
            (format "iny.server[tcp:%d,udp:%d,loop:%s]"
                    port port (name (native/wanted))))

          java.lang.AutoCloseable
          Closeable
          (close [_]
            (when-not (first (reset-vals! closed? true))
              (log/info "Shutting down Iny server")
              (-> ^Channel tcp-channel (.close) (.sync))
              (when http3 (-> ^Channel udp-channel (.close) (.sync)))
              (shutdown-gracefully parent-group)
              (shutdown-gracefully child-group)
              (shutdown-gracefully worker-group)))))
      (catch Throwable e
        @(shutdown-gracefully parent-group)
        @(shutdown-gracefully child-group)
        @(shutdown-gracefully worker-group)
        (throw e)))))
