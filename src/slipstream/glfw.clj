(ns slipstream.glfw
  (:require [jolt.ffi :as ffi]
            [slipstream.core :as core]
            [slipstream.ffi :as f]))

(defn- native-failure [op]
  (let [message (or (f/last-error) "unknown native error")]
    (throw (ex-info (str (name op) " failed: " message)
                    {:op op :native-error message}))))

(defn read-stdin-text []
  (let [text-p (f/read-stdin-text)]
    (if (or (nil? text-p) (zero? text-p))
      (when (seq (f/last-error))
        (native-failure :read-stdin-text))
      (try
        (ffi/ptr->string text-p)
        (finally
          (f/free-stdin-text text-p))))))

(defn run! [scene {:keys [title capture-path max-frames]
                   :or {title "slughorn" capture-path nil max-frames nil}}]
  (when-not (or (nil? max-frames)
                (and (integer? max-frames) (pos? max-frames)))
    (throw (ex-info ":max-frames must be a positive integer or nil"
                    {:max-frames max-frames})))
  (core/with-scene [compiled scene]
    (let [curve-precision (get-in compiled [:data :options :curve-precision] 32)]
      (core/call-with-native-layers compiled
        (fn [atlas-p layers-p n]
          (ffi/with-layout [view f/layers-view-layout]
            (ffi/write-field view f/layers-view-layout :data layers-p)
            (ffi/write-field view f/layers-view-layout :count n)
            (ffi/with-arena [arena]
              (let [title-p (ffi/string->ptr arena title)
                    capture-p (when capture-path
                                (ffi/string->ptr arena capture-path))]
                (when-not (zero? (f/glfw-run atlas-p view title-p curve-precision
                                             (or capture-p 0) (or max-frames 0)))
                  (native-failure :glfw-run!)))))))))
  nil)
