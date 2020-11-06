(ns iny.http.conversion
  (:import [java.nio.charset
            Charset]
           [io.netty.channel
            ChannelHandlerContext]
           [io.netty.buffer
            ByteBuf
            Unpooled]
           [io.netty.handler.codec.http
            HttpResponseStatus]))

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

(defprotocol WritableBody
  (^ByteBuf ->buffer [_] [_ _]))

(let [charset (Charset/forName "UTF-8")]
  (extend-protocol WritableBody
    (Class/forName "[B")
    (->buffer
      ([b]
        (Unpooled/copiedBuffer ^bytes b))
      ([b ctx]
        (doto (-> ^ChannelHandlerContext ctx
                  (.alloc)
                  (.ioBuffer (alength ^bytes b)))
              (.writeBytes ^bytes b))))

    nil
    (->buffer
      ([_]
        Unpooled/EMPTY_BUFFER)
      ([_ _]
        Unpooled/EMPTY_BUFFER))

    String
    (->buffer
      ([str]
        (Unpooled/copiedBuffer ^String str charset))
      ([str ctx]
        (->buffer ^bytes (.getBytes str) ctx)))))
