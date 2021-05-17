(ns iny.interop)

(defmacro ->typed-array
  [klass things]
  (let [^Class resolved (eval klass)]
    (with-meta
     (list 'into-array klass things)
     {:tag (.getName (.arrayType resolved))})))
