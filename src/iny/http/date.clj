(ns iny.http.date
  (:import [java.util
            Date
            TimeZone
            Locale]
           [java.text
            SimpleDateFormat]
           [java.util.concurrent
            TimeUnit]
           [java.util.concurrent.atomic
            AtomicReference]
           [io.netty.util
            AsciiString]
           [io.netty.util.concurrent
            FastThreadLocal]
           [io.netty.channel
            ChannelHandlerContext]))

(defonce ^FastThreadLocal date-format (FastThreadLocal.))
(defonce ^FastThreadLocal date-value (FastThreadLocal.))

(defmacro ref-get-or-set [^FastThreadLocal ftl or-value]
  `(if-let [^AtomicReference ref# (.get ~ftl)]
     (.get ref#)
     (let [new-ref# (AtomicReference. ~or-value)]
       (.set ~ftl new-ref#)
       (.get new-ref#))))

(defn ^SimpleDateFormat header-date-format
  "SimpleDateFormat isn't thread-safe so it has to be made for each worker"
  []
  (doto (SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss z" Locale/ENGLISH)
        (.setTimeZone (TimeZone/getTimeZone "GMT"))))

(defn formatted-header-date []
  (let [^SimpleDateFormat format
        (ref-get-or-set date-format (header-date-format))]
    (AsciiString. (.format format (Date.)))))

(defn ^CharSequence date-header-value [_opts]
  (ref-get-or-set date-value (formatted-header-date)))

(defn schedule-date-value-update
  [^ChannelHandlerContext ctx _opts]
  (let [ref (AtomicReference. (formatted-header-date))]
    ; header date is accurate to seconds
    ; so have it update every second
    (.set date-value ref)
    (.scheduleAtFixedRate (.executor ctx)
      #(.set ref (formatted-header-date))
      1000
      1000
      TimeUnit/MILLISECONDS)))
