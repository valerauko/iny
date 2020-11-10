(ns iny.http.method
  (:import [java.util
            Map
            Collections]))

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
