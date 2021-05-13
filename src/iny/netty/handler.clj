(ns iny.netty.handler
  (:import [io.netty.channel
            ChannelHandler
            ChannelInboundHandler
            ChannelOutboundHandler]))

(defmacro handler
  [klass init-methods spec-methods]
  (let [empty-impl# (merge
                     {'handlerAdded
                      `([_# _#])
                      'handlerRemoved
                      `([_# _#])
                      'exceptionCaught
                      `([_# ctx# ex#]
                        (.fireExceptionCaught ctx# ex#))}
                     init-methods)
        method-map# (reduce
                      (fn list->map [aggr# [name# & args-body#]]
                        (assoc aggr# name# args-body#))
                      empty-impl#
                      spec-methods)]

    `(reify
       ChannelHandler
       ~klass

       ~@(reduce-kv
          (fn map->list [aggr# name# body#]
            (->> (cons name# body#)
                 (conj aggr#)))
          []
          method-map#))))

(defmacro inbound
  [& methods]
  (let [empty-impl#
        {'channelRegistered
         `([_# ctx#]
           (.fireChannelRegistered ctx#))
         'channelUnregistered
         `([_# ctx#]
           (.fireChannelUnregistered ctx#))
         'channelActive
         `([_# ctx#]
           (.fireChannelActive ctx#))
         'channelInactive
         `([_# ctx#]
           (.fireChannelInactive ctx#))
         'channelRead
         `([_# ctx# msg#]
           (.fireChannelRead ctx# msg#))
         'channelReadComplete
         `([_# ctx#]
           (.fireChannelReadComplete ctx#))
         'userEventTriggered
         `([_# ctx# event#]
           (.fireUserEventTriggered ctx# event#))
         'channelWritabilityChanged
         `([_# ctx#]
           (.fireChannelWritabilityChanged ctx#))}]
    `(handler ChannelInboundHandler ~empty-impl# ~methods)))

(defmacro outbound
  [& methods]
  (let [empty-impl#
        {'bind
         `([_# ctx# local# promise#]
           (.bind ctx# local# promise#))
         'connect
         `([_# ctx# local# remote# promise#]
           (.connect ctx# local# remote# promise#))
         'disconnect
         `([_# ctx# promise#]
           (.disconnect ctx# promise#))
         'close
         `([_# ctx# promise#]
           (.disconnect ctx# promise#))
         'read
         `([_# ctx#]
           (.read ctx#))
         'write
         `([_# ctx# msg# promise#]
           (.write ctx# msg# promise#))
         'flush
         `([_# ctx#]
           (.flush ctx#))}]
    `(handler ChannelOutboundHandler ~empty-impl# ~methods)))
