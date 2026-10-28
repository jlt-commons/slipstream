(ns slipstream.glimmer-paint-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.ffi :as ffi]
            [slipstream.ffi :as f]
            [slipstream.glimmer-compiler :as compiler]
            [slipstream.glimmer-renderer :as renderer]
            [slipstream.glimmer-resources :as resources]))

(def ^:private red-blue [[0 [1 0 0 1]] [1 [0 0 1 1]]])
(def ^:private tolerance 5)

(defn- paint-cpu [paint]
  (compiler/compile-shape
    {:path [[:rect 0 0 1 1]]
     :fill paint
     :texture-width 128}))

(defn- upload-paint [context paint]
  (let [cpu (paint-cpu paint)
        handle (renderer/upload! context cpu)]
    {:cpu cpu
     :handle handle
     :paint (renderer/paint-texture! context handle)}))

(defn- release-paint [context resource]
  (renderer/release! context (:handle resource))
  (resources/release! (:cpu resource)))

(defn- command
  ([resource] (command resource 1))
  ([resource opacity]
   (let [base (if (zero? (:paint resource))
                (get-in resource [:cpu :root :color])
                [1 1 1 1])]
     {:handle (:handle resource)
      :paint (:paint resource)
      :color (assoc base 3 (* (nth base 3) opacity))
      :effect-param 0.75})))

(defn- capture [context resource opacity]
  (renderer/render! context [(command resource opacity)])
  (renderer/capture! context))

(defn- pixel [frame [x y]]
  (let [x (* x (/ (:width frame) 64))
        y (* y (/ (:height frame) 64))
        offset (* 4 (+ (* (- (:height frame) 1 y) (:width frame)) x))]
    (mapv #(bit-and 0xff (nth (:pixels frame) (+ offset %))) (range 4))))

(defn- check-pixel [frame position expected]
  (let [actual (pixel frame position)]
    (is (every? true? (map #(<= (Math/abs (- (int %1) (int %2))) tolerance)
                           actual expected))
        (str "pixel " position " expected " expected " but got " actual))))

(defn- frame-state [context]
  (ffi/with-layout [out f/gl-frame-state-layout]
    (when-not (zero? (f/gl-test-frame-state (:handle context) out))
      (throw (ex-info "gl-test-frame-state failed" {:error (f/last-error)})))
    {:shader-compilations (ffi/read-field out f/gl-frame-state-layout :shader-compilations)
     :resource-uploads (ffi/read-field out f/gl-frame-state-layout :resource-uploads)
     :paint-texture-uploads (ffi/read-field out f/gl-frame-state-layout :paint-texture-uploads)
     :effect-param (ffi/read-field out f/gl-frame-state-layout :effect-param)}))

(defmacro ^:private with-context [[context] & body]
  `(let [~context (slipstream.glimmer-renderer/create-context
                    {:title "slipstream paint goldens" :width 64 :height 64
                     :bounds [0 1 0 1] :visible false})]
     (try
       ~@body
       (finally
         (slipstream.glimmer-renderer/destroy! ~context)))))

(deftest flat-linear-and-opacity-golden-frames
  (with-context [context]
    (let [flat (upload-paint context [1 0 0 1])
          linear (upload-paint context {:type :linear :from [0 0] :to [1 0]
                                        :stops red-blue})]
      (try
        (is (zero? (:paint flat)))
        (check-pixel (capture context flat 1) [32 32] [255 0 0 255])
        (check-pixel (capture context flat 0.5) [32 32] [147 19 19 191])
        (let [frame (capture context linear 1)]
          (check-pixel frame [8 32] [225 0 30 255])
          (check-pixel frame [32 32] [128 0 128 255])
          (check-pixel frame [56 32] [30 0 225 255]))
        (check-pixel (capture context linear 0.5) [32 32] [83 19 83 191])
        (is (= 0.75 (:effect-param (frame-state context))))
        (finally
          (release-paint context flat)
          (release-paint context linear))))))

(deftest radial-sweep-and-affine-radial-golden-frames
  (with-context [context]
    (let [radial (upload-paint context {:type :radial :center [0.5 0.5]
                                        :inner-radius 0 :outer-radius 0.5
                                        :stops red-blue})
          sweep (upload-paint context {:type :sweep :center [0.5 0.5]
                                       :start-angle (- Math/PI) :end-angle Math/PI
                                       :stops red-blue})
          affine (upload-paint context {:type :affine-radial :center [0.5 0.5]
                                        :inner-radius 0
                                        :transform [2 0 0 1 0.5 0.5]
                                        :stops red-blue})
          outer-affine (upload-paint context {:type :affine-radial :center [0.5 0.5]
                                              :inner-radius 0 :outer-radius 0.5
                                              :transform [2 0 0 1 0.5 0.5]
                                              :stops red-blue})]
      (try
        (let [frame (capture context radial 1)]
          (check-pixel frame [32 32] [255 0 0 255])
          (check-pixel frame [56 32] [64 0 191 255])
          (check-pixel frame [32 56] [64 0 191 255]))
        (let [frame (capture context sweep 1)]
          (check-pixel frame [48 32] [128 0 128 255])
          (check-pixel frame [32 48] [191 0 64 255])
          (check-pixel frame [32 16] [64 0 191 255]))
        (let [frame (capture context affine 1)]
          (check-pixel frame [48 32] [128 0 128 255])
          (check-pixel frame [32 48] [191 0 64 255]))
        (let [frame (capture context outer-affine 1)]
          (check-pixel frame [48 32] [0 0 255 255])
          (check-pixel frame [32 48] [128 0 128 255]))
        (finally
          (release-paint context radial)
          (release-paint context sweep)
          (release-paint context affine)
          (release-paint context outer-affine))))))

(deftest gradient-transform-golden-frame
  (with-context [context]
    (let [vertical (upload-paint context {:type :linear :from [0 0] :to [1 0]
                                          :transform [0 0 1 1 0 0]
                                          :stops red-blue})]
      (try
        (let [frame (capture context vertical 1)]
          (check-pixel frame [32 8] [30 0 225 255])
          (check-pixel frame [32 32] [128 0 128 255])
          (check-pixel frame [32 56] [225 0 30 255]))
        (finally
          (release-paint context vertical))))))

(deftest repeat-reflect-tile-and-pattern-transform-golden-frames
  (with-context [context]
    (let [pattern (fn [repeat-x tile-width transform]
                    {:type :pattern :tile :tile
                     :tile-path [[:rect 0 0 tile-width 1]]
                     :cell [0 0 1 1]
                     :transform transform
                     :repeat-x repeat-x :repeat-y :repeat})
          repeated (upload-paint context (pattern :repeat 0.25 [1 0 0 1 0 0]))
          reflected (upload-paint context (pattern :reflect 0.25 [2 0 0 1 0 0]))
          transformed (upload-paint context (pattern :repeat 0.25 [2 0 0 1 0 0]))
          wider-tile (upload-paint context (pattern :repeat 0.5 [1 0 0 1 0 0]))]
      (try
        (let [repeat-frame (capture context repeated 1)
              reflect-frame (capture context reflected 1)]
          (check-pixel repeat-frame [8 32] [255 255 255 255])
          (check-pixel repeat-frame [24 32] [38 38 38 255])
          (check-pixel repeat-frame [56 32] [38 38 38 255])
          (check-pixel reflect-frame [4 32] [255 255 255 255])
          (check-pixel reflect-frame [24 32] [38 38 38 255])
          (check-pixel reflect-frame [60 32] [255 255 255 255]))
        (check-pixel (capture context transformed 1) [4 32] [255 255 255 255])
        (check-pixel (capture context transformed 1) [24 32] [38 38 38 255])
        (check-pixel (capture context wider-tile 1) [24 32] [255 255 255 255])
        (finally
          (doseq [resource [repeated reflected transformed wider-tile]]
            (release-paint context resource)))))))

(deftest stop-changes-invalidate-only-dependent-paint-resources
  (with-context [context]
    (let [a (upload-paint context {:type :linear :from [0 0] :to [1 0]
                                   :stops red-blue})
          b (upload-paint context {:type :radial :center [0.5 0.5]
                                   :inner-radius 0 :outer-radius 0.5
                                   :stops [[0 [0 1 0 1]] [1 [1 1 0 1]]]})
          program (renderer/program! context :paint)
          before-b (capture context b 1)
          before-handle-b (:handle b)
          before-paint-b (:paint b)
          before-state (frame-state context)
          replacement (upload-paint context {:type :linear :from [0 0] :to [1 0]
                                             :stops [[0 [0 1 0 1]] [1 [1 0 1 1]]]})]
      (try
        (release-paint context a)
        (let [after-state (frame-state context)]
          (is (not= (:paint a) (:paint replacement)))
          (is (= before-handle-b (:handle b)))
          (is (= before-paint-b (:paint b)))
          (is (= program (renderer/program! context :paint)))
          (is (= (:shader-compilations before-state)
                 (:shader-compilations after-state)))
          (is (= (inc (:resource-uploads before-state))
                 (:resource-uploads after-state)))
          (is (= (inc (:paint-texture-uploads before-state))
                 (:paint-texture-uploads after-state))))
        (is (= (vec (:pixels before-b)) (vec (:pixels (capture context b 1)))))
        (check-pixel (capture context replacement 1) [32 32] [128 127 128 255])
        (finally
          (release-paint context replacement)
          (release-paint context b))))))

(deftest tile-changes-invalidate-only-dependent-paint-resources
  (with-context [context]
    (let [pattern (fn [tile-width]
                    {:type :pattern :tile :tile
                     :tile-path [[:rect 0 0 tile-width 1]]
                     :cell [0 0 1 1] :transform [1 0 0 1 0 0]
                     :repeat-x :repeat :repeat-y :repeat})
          a (upload-paint context (pattern 0.25))
          b (upload-paint context {:type :linear :from [0 0] :to [1 0]
                                   :stops red-blue})
          program (renderer/program! context :paint)
          before-b (capture context b 1)
          before-handle-b (:handle b)
          before-paint-b (:paint b)
          before-state (frame-state context)
          replacement (upload-paint context (pattern 0.5))]
      (try
        (release-paint context a)
        (let [after-state (frame-state context)]
          (is (not= (:paint a) (:paint replacement)))
          (is (= before-handle-b (:handle b)))
          (is (= before-paint-b (:paint b)))
          (is (= program (renderer/program! context :paint)))
          (is (= (:shader-compilations before-state)
                 (:shader-compilations after-state)))
          (is (= (inc (:resource-uploads before-state))
                 (:resource-uploads after-state)))
          (is (= (:paint-texture-uploads before-state)
                 (:paint-texture-uploads after-state))))
        (is (= (vec (:pixels before-b)) (vec (:pixels (capture context b 1)))))
        (check-pixel (capture context replacement 1) [24 32] [255 255 255 255])
        (finally
          (release-paint context replacement)
          (release-paint context b))))))

(deftest programs-and-paint-textures-are-context-local
  (let [a (renderer/create-context {:width 64 :height 64 :bounds [0 1 0 1] :visible false})
        b (renderer/create-context {:width 64 :height 64 :bounds [0 1 0 1] :visible false})
        paint {:type :linear :from [0 0] :to [1 0] :stops red-blue}
        resource-a (upload-paint a paint)
        resource-b (upload-paint b paint)]
    (try
      (is (identical? (:cpu resource-a) (:cpu resource-b)))
      (is (not= (:handle resource-a) (:handle resource-b)))
      (is (not= (:paint resource-a) (:paint resource-b)))
      (is (= (renderer/program! a :paint) (renderer/program! a :paint)))
      (is (not= (renderer/program! a :paint) (renderer/program! b :paint)))
      (check-pixel (capture a resource-a 1) [32 32] [128 0 128 255])
      (check-pixel (capture b resource-b 1) [32 32] [128 0 128 255])
      (renderer/destroy! a)
      (check-pixel (capture b resource-b 1) [32 32] [128 0 128 255])
      (finally
        (renderer/destroy! a)
        (release-paint b resource-b)
        (renderer/destroy! b)
        (resources/release! (:cpu resource-a))))))

(deftest malformed-and-unknown-paints-report-scene-paths
  (doseq [paint [:missing {:type :unknown} {:type :affine-radial :center [0 0]
                                            :stops red-blue}
                 {:type :pattern :tile :missing :cell [0 0 1 1]}]]
    (let [error (try
                  (paint-cpu paint)
                  nil
                  (catch Throwable error error))]
      (is (some? error) (pr-str paint))
      (is (vector? (:scene-path (ex-data error)))
          (str "missing scene path for " (pr-str paint) ": " (ex-data error))))))

(deftest advanced-draws-require-their-paint-reference
  (with-context [context]
    (let [resource (upload-paint context {:type :linear :from [0 0] :to [1 0]
                                          :stops red-blue})]
      (try
        (let [error (try
                      (renderer/render! context [{:handle (:handle resource)
                                                  :color [1 0 0 1]}])
                      nil
                      (catch Throwable error error))]
          (is (some? error))
          (is (vector? (:scene-path (ex-data error))))
          (is (re-find #"paint" (str (:native-error (ex-data error))))))
        (check-pixel (capture context resource 1) [32 32] [128 0 128 255])
        (finally
          (release-paint context resource))))))

(deftest unsupported-effect-ids-report-a-scene-path
  (with-context [context]
    (let [resource (upload-paint context [1 0 0 1])]
      (try
        (let [error (try
                      (renderer/render! context [(assoc (command resource) :effect-id 1)])
                      nil
                      (catch Throwable error error))]
          (is (some? error))
          (is (= [0 :effect-id] (:scene-path (ex-data error)))))
        (finally
          (release-paint context resource))))))
