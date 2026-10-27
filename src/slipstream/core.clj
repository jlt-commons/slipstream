(ns slipstream.core
  (:require [slipstream.ffi :as f]))

(defrecord Atlas [handle destroyed?])

(defn- native-failure [op]
  (let [message (or (f/last-error) "unknown native error")]
    (throw (ex-info (str (name op) " failed: " message)
                    {:op op :native-error message}))))

(defn- live-handle [^Atlas atlas op]
  (when (or @(:destroyed? atlas)
            (nil? (:handle atlas))
            (zero? (:handle atlas)))
    (throw (ex-info "atlas is destroyed" {:op op})))
  (:handle atlas))

(defn make-atlas [& [{:keys [texture-width] :or {texture-width 512}}]]
  (let [handle (f/atlas-new texture-width)]
    (when (or (nil? handle) (zero? handle))
      (native-failure :make-atlas))
    (->Atlas handle (atom false))))

(defn destroy! [^Atlas atlas]
  (when (compare-and-set! (:destroyed? atlas) false true)
    (when-not (zero? (f/atlas-destroy (:handle atlas)))
      (native-failure :destroy!)))
  atlas)

(defn build! [^Atlas atlas]
  (let [handle (live-handle atlas :build!)]
    (when-not (zero? (f/atlas-build handle))
      (native-failure :build!)))
  atlas)

(defn built? [^Atlas atlas]
  (let [result (f/atlas-built? (live-handle atlas :built?))]
    (when (and (false? result) (seq (f/last-error)))
      (native-failure :built?))
    result))

(defmacro with-atlas [[atlas opts] & body]
  `(let [~atlas (make-atlas ~opts)]
     (try
       ~@body
       (finally
         (destroy! ~atlas)))))
