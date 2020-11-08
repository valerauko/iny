(ns iny.http.method
  (:import [java.util
            Collections
            HashMap]))

(def ^HashMap http-methods
  (-> {"OPTIONS" :options
       "GET" :get
       "HEAD" :head
       "POST" :post
       "PUT" :put
       "PATCH" :patch
       "DELETE" :delete
       "TRACE" :trace
       "CONNECT" :connect}
      (HashMap.)
      (Collections/unmodifiableMap)))
