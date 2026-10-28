(ns slipstream.glimmer-input-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [slipstream.ffi :as f]
            [slipstream.glimmer-renderer :as renderer]))

(defmacro ^:private with-context [[binding options] & body]
  `(let [~binding (renderer/create-context ~options)]
     (try
       ~@body
       (finally
         (renderer/destroy! ~binding)))))

(defn- set-input! [context input]
  (ffi/with-layout [native-input f/gl-input-layout]
    (doseq [[field value] input]
      (ffi/write-field native-input f/gl-input-layout field value))
    (is (zero? (f/gl-test-set-input (:handle context) native-input)))))

(defn- frame-sizes [context]
  (ffi/with-layout [state f/gl-frame-state-layout]
    (is (zero? (f/gl-test-frame-state (:handle context) state)))
    {:window (mapv #(ffi/read-field state f/gl-frame-state-layout [:window-size %])
                   (range 2))
     :framebuffer (mapv #(ffi/read-field state f/gl-frame-state-layout [:framebuffer-size %])
                        (range 2))}))

(defn- close? [a b]
  (< (Math/abs (- (double a) (double b))) 0.000001))

(deftest native-input-entry-points-reject-null-handles
  (is (not (zero? (f/gl-input-snapshot 0 0))))
  (is (not (zero? (f/gl-request-close 0))))
  (is (not (zero? (f/gl-test-set-input 0 0)))))

(deftest input-snapshot-returns-cursor-buttons-and-keys-and-consumes-scroll
  (with-context [context {:visible false :width 320 :height 240}]
    (set-input! context {:x 12.5 :y 40.25 :scroll-x 1.5 :scroll-y -2.25
                         :left 1 :right 0 :middle 1 :space 1 :r 1})
    (is (= {:x 12.5 :y 40.25 :scroll-x 1.5 :scroll-y -2.25
            :left true :right false :middle true :space true :r true}
           (renderer/input-snapshot context)))
    (is (= {:x 12.5 :y 40.25 :scroll-x 0.0 :scroll-y 0.0
            :left true :right false :middle true :space true :r true}
           (renderer/input-snapshot context)))))

(deftest request-close-stops-the-loop-and-is-idempotent
  (with-context [context {:visible false :width 320 :height 240}]
    (is (zero? (renderer/run-loop! context
                 {:max-frames 1
                  :on-start (fn []
                              (is (nil? (renderer/request-close! context)))
                              (is (nil? (renderer/request-close! context))))})))
    (is (true? (renderer/should-close? context)))
    (is (nil? (renderer/request-close! context)))))

(deftest cursor-maps-window-coordinates-to-default-y-up-bounds
  (with-context [context {:visible false :width 320 :height 240}]
    (let [{[window-width window-height] :window
           [framebuffer-width framebuffer-height] :framebuffer} (frame-sizes context)]
      (set-input! context {:x (* 0.25 window-width) :y (* 0.75 window-height)
                           :scroll-x 0.0 :scroll-y 0.0
                           :left 0 :right 0 :middle 0 :space 0 :r 0})
      (let [[x y] (renderer/cursor->bounds context nil)
            aspect (/ (double framebuffer-width) framebuffer-height)]
        (is (close? (* 0.25 aspect) x))
        (is (close? 0.25 y))))))

(deftest cursor-maps-window-coordinates-to-explicit-y-up-bounds
  (with-context [context {:visible false :width 320 :height 240}]
    (let [{[window-width window-height] :window} (frame-sizes context)]
      (set-input! context {:x (* 0.75 window-width) :y (* 0.25 window-height)
                           :scroll-x 0.0 :scroll-y 0.0
                           :left 0 :right 0 :middle 0 :space 0 :r 0})
      (let [[x y] (renderer/cursor->bounds context [-2 4 3 7])]
        (is (close? 2.5 x))
        (is (close? 6.0 y))))))
