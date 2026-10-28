(ns slipstream.demoscene-metrics-test
  (:require [clojure.test :refer [deftest is]]
            [slipstream.example.demoscene-metrics :as metrics]
            [slipstream.ffi :as f]
            [slipstream.glimmer-compiler :as compiler]
            [slipstream.glimmer-effects :as effects]
            [slipstream.glimmer-renderer :as renderer]
            [slipstream.glimmer-resources :as resources]))

(defn- frame-state
  ([] (frame-state {}))
  ([overrides]
   (merge {:viewport [0 0 640 480]
           :framebuffer-size [640 480]
           :window-size [320 240]
           :projection (vec (repeat 16 0.0))
           :shader-compilations 6
           :resource-uploads 10
           :paint-texture-uploads 4
           :live-resources 2
           :live-effects 1
           :draw-commands 8
           :geometry-commands 6
           :group-commands 2}
          overrides)))

(deftest frame-percentiles-use-the-bounded-measured-window
  (let [collector (metrics/new-collector {:warmup-ms 0 :max-samples 10})]
    (doseq [now [0 40 50 80 100]]
      (metrics/sample-frame! collector (frame-state) now))
    (let [snapshot (metrics/metrics-snapshot collector)]
      (is (= 4 (:sample-count snapshot)))
      (is (= 20.0 (:last-frame-ms snapshot)))
      (is (= 25.0 (:mean-frame-ms snapshot)))
      (is (= 40.0 (:fps snapshot)))
      (is (= {:p50 20.0 :p95 40.0 :p99 40.0}
             (:frame-percentiles-ms snapshot))))))

(deftest warm-up-excludes-initialization-and-defaults-to-2000-ms
  (let [collector (metrics/new-collector {})]
    (is (= 2000 (:warmup-ms (metrics/metrics-snapshot collector))))
    (metrics/sample-frame! collector (frame-state) 0)
    (metrics/sample-frame! collector (frame-state) 1000)
    (metrics/sample-frame! collector (frame-state) 1999)
    (is (:warming-up? (metrics/metrics-snapshot collector)))
    (is (zero? (:sample-count (metrics/metrics-snapshot collector))))
    (metrics/sample-frame! collector (frame-state) 2000)
    (metrics/sample-frame! collector (frame-state) 2010)
    (let [snapshot (metrics/metrics-snapshot collector)]
      (is (not (:warming-up? snapshot)))
      (is (= 2 (:sample-count snapshot)))
      (is (= 5.5 (:mean-frame-ms snapshot)))
      (is (= {:p50 1.0 :p95 10.0 :p99 10.0}
             (:frame-percentiles-ms snapshot))))))

(deftest sample-window-evicts-oldest-durations
  (let [collector (metrics/new-collector {:warmup-ms 0 :max-samples 3})]
    (doseq [now [0 10 30 60 100]]
      (metrics/sample-frame! collector (frame-state) now))
    (let [snapshot (metrics/metrics-snapshot collector)]
      (is (= 3 (:sample-count snapshot)))
      (is (= 30.0 (:mean-frame-ms snapshot)))
      (is (= {:p50 30.0 :p95 40.0 :p99 40.0}
             (:frame-percentiles-ms snapshot))))))

(deftest counters-report-cumulative-values-and-measured-deltas-separately
  (let [collector (metrics/new-collector {:warmup-ms 0})]
    (metrics/sample-frame! collector (frame-state) 0)
    (metrics/sample-frame! collector
                           (frame-state {:shader-compilations 8
                                         :resource-uploads 15
                                         :paint-texture-uploads 7})
                           10)
    (metrics/sample-frame! collector
                           (frame-state {:shader-compilations 9
                                         :resource-uploads 15
                                         :paint-texture-uploads 10
                                         :live-resources 4
                                         :live-effects 2
                                         :draw-commands 12
                                         :geometry-commands 8
                                         :group-commands 4})
                           20)
    (let [snapshot (metrics/metrics-snapshot collector)]
      (is (= {:shader-compilations 9 :resource-uploads 15 :paint-texture-uploads 10}
             (:cumulative-counters snapshot)))
      (is (= {:shader-compilations 3 :resource-uploads 5 :paint-texture-uploads 6}
             (:counter-deltas snapshot)))
      (is (= 4 (:live-resources snapshot)))
      (is (= 2 (:live-effects snapshot)))
      (is (= 12 (:draw-commands snapshot)))
      (is (= 8 (:geometry-commands snapshot)))
      (is (= 4 (:group-commands snapshot))))))

(deftest hud-refreshes-at-most-four-times-per-second
  (let [collector (metrics/new-collector {:warmup-ms 0})]
    (metrics/sample-frame! collector (frame-state) 0)
    (let [initial (metrics/hud-status collector)]
      (is (string? initial))
      (metrics/sample-frame! collector (frame-state {:live-resources 3}) 10)
      (is (= initial (metrics/hud-status collector)))
      (doseq [now [20 100 249]]
        (metrics/sample-frame! collector (frame-state {:live-resources 4}) now)
        (is (= initial (metrics/hud-status collector))))
      (metrics/sample-frame! collector (frame-state {:live-resources 4}) 250)
      (let [quarter-second (metrics/hud-status collector)]
        (is (not= initial quarter-second))
        (is (re-find #"FPS" quarter-second))
        (is (re-find #"resources=4" quarter-second))
        (metrics/sample-frame! collector (frame-state {:live-resources 5}) 400)
        (is (= quarter-second (metrics/hud-status collector)))
        (metrics/sample-frame! collector (frame-state {:live-resources 5}) 500)
        (is (re-find #"resources=5" (metrics/hud-status collector)))))))

(deftest hud-interval-option-cannot-bypass-four-hz-limit
  (let [collector (metrics/new-collector {:warmup-ms 0 :hud-interval-ms 0})]
    (metrics/sample-frame! collector (frame-state) 0)
    (let [initial (metrics/hud-status collector)]
      (metrics/sample-frame! collector (frame-state {:live-resources 3}) 1)
      (is (= initial (metrics/hud-status collector)))
      (metrics/sample-frame! collector (frame-state {:live-resources 4}) 249)
      (is (= initial (metrics/hud-status collector)))
      (metrics/sample-frame! collector (frame-state {:live-resources 4}) 250)
      (is (re-find #"resources=4" (metrics/hud-status collector))))))

(deftest reset-clears-measurements-but-retains-native-cumulative-counters
  (let [collector (metrics/new-collector {:warmup-ms 0})]
    (metrics/sample-frame! collector (frame-state) 0)
    (metrics/sample-frame! collector
                           (frame-state {:shader-compilations 8 :resource-uploads 12}) 10)
    (metrics/reset-metrics! collector)
    (let [reset (metrics/metrics-snapshot collector)]
      (is (zero? (:sample-count reset)))
      (is (zero? (:mean-frame-ms reset)))
      (is (= {:shader-compilations 0 :resource-uploads 0 :paint-texture-uploads 0}
             (:counter-deltas reset)))
      (is (= {:shader-compilations 8 :resource-uploads 12 :paint-texture-uploads 4}
             (:cumulative-counters reset))))
    (metrics/sample-frame! collector
                           (frame-state {:shader-compilations 8 :resource-uploads 12}) 1000)
    (metrics/sample-frame! collector
                           (frame-state {:shader-compilations 10 :resource-uploads 15}) 1010)
    (is (= {:shader-compilations 2 :resource-uploads 3 :paint-texture-uploads 0}
           (:counter-deltas (metrics/metrics-snapshot collector))))))

(deftest general-frame-state-reports-real-native-live-counts
  (let [context (renderer/create-context
                  {:title "slipstream frame state" :width 64 :height 64 :visible false})
        cpu (compiler/compile-shape
              {:path [[:rect 0.1 0.1 0.8 0.8]]
               :fill [1 0 0 1]
               :texture-width 128})]
    (try
      (let [initial (renderer/frame-state context)]
        (is (= #{:viewport :framebuffer-size :window-size :projection
                 :shader-compilations :resource-uploads :paint-texture-uploads
                 :live-resources :live-effects
                 :draw-commands :geometry-commands :group-commands}
               (set (keys initial))))
        (is (zero? (:live-resources initial)))
        (is (zero? (:live-effects initial)))
        (let [handle (renderer/upload! context cpu)]
          (try
            (effects/effect-program!
              context
              {:id 1 :param 1
               :fragment-hook
               "vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) { return color; }"})
            (let [populated (renderer/frame-state context)]
              (is (= 1 (:live-resources populated)))
              (is (= 1 (:live-effects populated)))
              (is (pos? (:shader-compilations populated)))
              (is (= 1 (:resource-uploads populated))))
            (finally
              (renderer/release! context handle)))
          (let [released (renderer/frame-state context)]
            (is (zero? (:live-resources released)))
            (is (= 1 (:live-effects released))))))
      (finally
        (renderer/destroy! context)
        (resources/release! cpu)))))

(deftest frame-state-counts-actual-commands-and-clears-no-op-frames
  (let [context (renderer/create-context
                  {:title "slipstream command counts" :width 64 :height 64 :visible false})
        cpu (compiler/compile-shape
              {:path [[:rect 0.1 0.1 0.8 0.8]]
               :fill [1 0 0 1]
               :texture-width 128})
        counts #(select-keys % [:draw-commands :geometry-commands :group-commands])]
    (try
      (let [handle (renderer/upload! context cpu)]
        (try
          (let [draw {:handle handle}
                commands [draw
                          {:begin-group {:type :rect :params [0 0 1 1]}}
                          draw
                          {:begin-group nil}
                          draw
                          {:end-group :src-over}
                          {:end-group :screen}]]
            (renderer/render! context commands)
            (is (= {:draw-commands 7 :geometry-commands 3 :group-commands 4}
                   (counts (renderer/frame-state context))))
            (renderer/render! context [])
            (is (= {:draw-commands 0 :geometry-commands 0 :group-commands 0}
                   (counts (renderer/frame-state context))))
            (renderer/render! context commands)
            (is (zero? (f/gl-test-resize (:handle context) 0 0)))
            (renderer/render! context [draw])
            (is (= {:draw-commands 0 :geometry-commands 0 :group-commands 0}
                   (counts (renderer/frame-state context)))))
          (finally
            (renderer/release! context handle))))
      (finally
        (renderer/destroy! context)
        (resources/release! cpu)))))
