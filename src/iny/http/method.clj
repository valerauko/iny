(ns iny.http.method
  (:import [java.util
            Map
            Collections]
           [io.netty.handler.codec.http
            HttpRequest
            HttpMethod]))

(def ^Map http-methods
  (-> {"OPTIONS" :options
       "GET" :get
       "HEAD" :head
       "POST" :post
       "PUT" :put
       "PATCH" :patch
       "DELETE" :delete
       "TRACE" :trace
       "CONNECT" :connect}
      (Collections/unmodifiableMap)))

(defprotocol MethodCheck
  (^boolean get? [_]))

(extend-protocol MethodCheck
  HttpRequest
  (get? [req]
    (.equals (.method req) HttpMethod/GET)))
