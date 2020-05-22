(ns iny.runner
  (:require [clj-async-profiler.core :as prof]
            [jsonista.core :as json])
  (:import [java.io
            IOException]
           [java.nio
            ByteBuffer]
           [java.nio.charset
            Charset]
           [io.netty.bootstrap
            ServerBootstrap]
           [io.netty.buffer
            ByteBuf
            Unpooled
            AdvancedLeakAwareByteBuf]
           [io.netty.channel
            MultithreadEventLoopGroup
            ChannelPipeline
            ChannelInitializer
            ChannelFuture
            ChannelFutureListener
            ChannelHandlerContext
            ChannelHandler
            ChannelInboundHandler]
           [io.netty.channel.nio
            NioEventLoopGroup]
           [io.netty.channel.epoll
            Epoll
            EpollEventLoopGroup
            EpollServerSocketChannel]
           [io.netty.channel.socket
            SocketChannel]
           [io.netty.channel.socket.nio
            NioServerSocketChannel]
           [io.netty.handler.flush
            FlushConsolidationHandler]
           [io.netty.handler.codec.http
            HttpUtil
            HttpResponseEncoder
            HttpServerCodec
            HttpServerExpectContinueHandler
            HttpContent
            LastHttpContent
            HttpRequest
            HttpVersion
            HttpResponseStatus
            DefaultHttpHeaders
            DefaultHttpContent
            DefaultHttpResponse
            DefaultFullHttpResponse
            DefaultLastHttpContent])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn my-handler [& _]
  {:status 200
   :body (json/write-value-as-bytes {:message "hello"})
   :headers {"content-type" "text/plain"}})

(def version "0.1.0")

(defprotocol WritableBody
  (^ByteBuf ->buffer [_]))

(let [charset (Charset/forName "UTF-8")]
  (extend-protocol WritableBody
    (Class/forName "[B")
    (->buffer [b]
      (Unpooled/copiedBuffer ^bytes b))

    nil
    (->buffer [_]
      Unpooled/EMPTY_BUFFER)

    String
    (->buffer [str]
      (Unpooled/copiedBuffer ^String str charset))))

(defprotocol ResponseStatus
  (^HttpResponseStatus ->status [_]))

(extend-protocol ResponseStatus
  nil
  (->status [_] HttpResponseStatus/OK)

  HttpResponseStatus
  (->status [status] status)

  Integer
  Long
  (->status [number] (HttpResponseStatus/valueOf number)))

(let [base-headers (DefaultHttpHeaders. false)]
  (defn ^ChannelFuture respond
    [^ChannelHandlerContext ctx
     {:keys [body headers status]}]
    (let [status (->status status)
          buffer (->buffer body)
          response (DefaultHttpResponse.
                    HttpVersion/HTTP_1_1
                    status
                    (.copy base-headers))]
      (HttpUtil/setContentLength response (.readableBytes buffer))
      (.write ctx response (.voidPromise ctx))
      (.write ctx (DefaultHttpContent. buffer) (.voidPromise ctx))
      (.writeAndFlush ctx LastHttpContent/EMPTY_LAST_CONTENT))))

(defn ^ChannelInboundHandler http-handler
  [user-handler]
  (let [keep-alive? (atom false)]
    (reify
      ChannelInboundHandler

      (handlerAdded [_ ctx])
      (handlerRemoved [_ ctx])
      (exceptionCaught [_ ctx ex]
        (when-not (instance? IOException ex)
          (throw ex)))
      (channelRegistered [_ ctx])
      (channelUnregistered [_ ctx])
      (channelActive [_ ctx])
      (channelInactive [_ ctx])
      (channelRead [_ ctx msg]
        (cond
          (instance? HttpRequest msg)
            (let []
              (reset! keep-alive? (HttpUtil/isKeepAlive msg)))
          (instance? LastHttpContent msg)
            (let [ftr (respond ctx (user-handler))]
              (when-not keep-alive?
                (.addListener ftr ChannelFutureListener/CLOSE)))
          ; (instance? HttpContent msg)
          ;   (println "fuga")
          :else
            (.fireChannelRead ctx msg)
         ))
      (channelReadComplete [_ ctx])
      (userEventTriggered [_ ctx event])
      (channelWritabilityChanged [_ ctx]))))

(defn server-pipeline
  [user-handler]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel ch]
      (let [pipeline (.pipeline ch)]
        (.addLast pipeline "optimize-flushes" (FlushConsolidationHandler.))
        (.addLast pipeline "http-codec" (HttpServerCodec.))
        (.addLast pipeline "continue" (HttpServerExpectContinueHandler.))
        (.addLast pipeline "my-handler" (http-handler user-handler))))))

(let [cores (.availableProcessors (Runtime/getRuntime))
      epoll? (Epoll/isAvailable)
      socket-chan (if epoll? EpollServerSocketChannel NioServerSocketChannel)]
  (defn -main
    [& _]
    (let [parent-group (if epoll?
                         (EpollEventLoopGroup. (* 2 cores))
                         (NioEventLoopGroup. (* 2 cores)))
          child-group (if epoll?
                        (EpollEventLoopGroup. (* 3 cores))
                        (NioEventLoopGroup. (* 3 cores)))
          port 8080]
      (try
        (let [boot (doto (ServerBootstrap.)
                         (.group ^MultithreadEventLoopGroup parent-group
                                 ^MultithreadEventLoopGroup child-group)
                         (.channel socket-chan)
                         (.childHandler (server-pipeline my-handler)))
              channel (-> boot (.bind port) .sync .channel)]
          (fn closer []
            (-> channel .close .sync)
            (.shutdownGracefully ^MultithreadEventLoopGroup parent-group)
            (.shutdownGracefully ^MultithreadEventLoopGroup child-group)))
        (catch Exception e
          @(.shutdownGracefully ^MultithreadEventLoopGroup parent-group)
          @(.shutdownGracefully ^MultithreadEventLoopGroup child-group))))))
