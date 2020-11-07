(ns iny.http.status
  (:import [io.netty.handler.codec.http
            HttpResponseStatus]))

(defprotocol ResponseStatus
  (^io.netty.handler.codec.http.HttpResponseStatus ->status [_]))

(extend-protocol ResponseStatus
  nil
  (->status [_] HttpResponseStatus/OK)

  HttpResponseStatus
  (->status [status] status)

  Integer
  Long
  (->status [number] (HttpResponseStatus/valueOf number)))
