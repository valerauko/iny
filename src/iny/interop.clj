(ns iny.interop)

(defmacro ->typed-array
  [klass things]
  (let [^Class resolved (resolve klass)]
    (with-meta
     (list 'into-array resolved things)
     {:tag (str "[L" (.getName resolved) ";")})))
