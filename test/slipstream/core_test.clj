(ns slipstream.core-test
  (:require [clojure.test :refer [deftest is]]
            [slipstream.core :as slug]))

(defn- capture-ex-info [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      e)))

(deftest atlas-creation-and-build
  (let [atlas (atom nil)]
    (slug/with-atlas [a {:texture-width 64}]
      (reset! atlas a)
      (is (false? (slug/built? a)))
      (slug/build! a)
      (is (true? (slug/built? a))))
    (is (true? @(:destroyed? @atlas)))))

(deftest built-native-failure-throws-ex-info
  (let [atlas (slug/make-atlas)]
    (try
      (with-redefs [slipstream.ffi/atlas-built? (constantly false)
                    slipstream.ffi/last-error (constantly "injected native failure")]
        (let [error (capture-ex-info #(slug/built? atlas))]
          (is (some? error))
          (is (= :built? (:op (ex-data error))))
          (is (= "injected native failure" (:native-error (ex-data error))))))
      (finally
        (slug/destroy! atlas)))))

(deftest destroy-is-idempotent
  (let [atlas (slug/make-atlas)]
    (slug/destroy! atlas)
    (slug/destroy! atlas)
    (is (true? @(:destroyed? atlas)))))

(deftest build-after-destroy-throws-ex-info
  (let [atlas (slug/make-atlas)]
    (slug/destroy! atlas)
    (let [error (capture-ex-info #(slug/build! atlas))]
      (is (some? error))
      (is (= :build! (:op (ex-data error)))))))

(deftest zero-texture-width-surfaces-native-error
  (let [error (capture-ex-info #(slug/make-atlas {:texture-width 0}))]
    (is (some? error))
    (is (= :make-atlas (:op (ex-data error))))
    (is (seq (:native-error (ex-data error))))))

(deftest with-atlas-cleans-up-on-exception
  (let [atlas (atom nil)]
    (is (thrown-with-msg? Exception #"boom"
          (slug/with-atlas [a {}]
            (reset! atlas a)
            (throw (ex-info "boom" {})))))
    (is (true? @(:destroyed? @atlas)))))

(def ^:private rectangle-curves
  [[0 0 0.5 0 1 0]
   [1 0 1 0.5 1 1]
   [1 1 0.5 1 0 1]
   [0 1 0 0.5 0 0]])

(defn- close? [a b]
  (< (abs (- a b)) 0.00001))

(defn- floats-at [vertices offset values]
  (every? true? (map-indexed #(close? (aget vertices (+ offset %1)) %2) values)))

(deftest shape-inspection-and-texture-copies
  (let [copies (atom nil)]
    (slug/with-atlas [a {:texture-width 128}]
      (slug/add-shape! a :rect rectangle-curves {})
      (is (nil? (slug/shape a :missing)))
      (slug/build! a)
      (let [s (slug/shape a :rect)]
        (is (close? 0.0 (:bearing-x s)))
        (is (close? 1.0 (:bearing-y s)))
        (is (close? 1.0 (:width s)))
        (is (close? 1.0 (:height s)))
        (is (close? 1.0 (:advance s)))
        (is (= [1 1] [(:band-max-x s) (:band-max-y s)]))
        (is (close? 32.0 (:band-scale-x s)))
        (is (close? 32.0 (:band-scale-y s))))
      (let [curve (slug/texture a :curve)
            band (slug/texture a :band)
            borrowed (slug/texture a :curve {:copy? false})]
        (is (= [128 0 :rgba32f] [(:width curve) (:depth curve) (:format curve)]))
        (is (= [128 0 :rg16ui] [(:width band) (:depth band) (:format band)]))
        (is (pos? (:height curve)))
        (is (pos? (:height band)))
        (is (= (* 16 (:width curve) (:height curve)) (alength (:bytes curve))))
        (is (= (* 4 (:width band) (:height band)) (alength (:bytes band))))
        (is (= (:byte-count curve) (:byte-count borrowed)))
        (is (integer? (:bytes borrowed)))
        (reset! copies {:curve (:bytes curve) :curve-snapshot (vec (:bytes curve))
                        :band (:bytes band) :band-snapshot (vec (:bytes band))})))
    (is (= (:curve-snapshot @copies) (vec (:curve @copies))))
    (is (= (:band-snapshot @copies) (vec (:band @copies))))))

(deftest composite-mesh-matches-reference-layout
  (slug/with-atlas [a {:texture-width 128}]
    (slug/add-shape! a "rect" rectangle-curves {})
    (slug/build! a)
    (let [input [{:key "rect" :color [1 0 0 1] :transform [0 0 0]
                  :scale 1 :effect-id 7}
                 {:key "rect" :color [0 1 0 1] :transform [2 3 0]
                  :scale 2 :effect-id 9}]]
      (slug/add-composite! a :pair input {:advance 4})
      (let [c (slug/composite a :pair)]
        (is (= 4.0 (:advance c)))
        (is (= 2 (count (:layers c))))
        (is (= "rect" (get-in c [:layers 0 :key])))
        (is (= [1.0 0.0 0.0 1.0] (get-in c [:layers 0 :color]))))
      (is (= 2 (count (slug/layers a :pair))))
      (let [{:keys [vertices indices stride attributes]} (slug/mesh a :pair)]
        (is (= 80 stride))
        (is (= 160 (alength vertices)))
        (is (= [0 1 2 0 2 3 4 5 6 4 6 7] (vec indices)))
        (is (= {:location 0 :offset 0 :size 3 :type :float} (:position attributes)))
        (is (= {:location 5 :offset 76 :size 1 :type :float} (:effect-id attributes)))
        (is (floats-at vertices 0 [-0.01 -0.01 0.0]))
        (is (floats-at vertices 3 [1.0 0.0 0.0 1.0]))
        (is (floats-at vertices 7 [-0.01 -0.01 0.0 0.0]))
        (is (floats-at vertices 11 [32.0 32.0 0.0 0.0]))
        (is (floats-at vertices 17 [1.0 1.0 7.0]))
        (is (floats-at vertices 80 [3.98 5.98 0.0]))
        (is (floats-at vertices 83 [0.0 1.0 0.0 1.0]))
        (is (floats-at vertices 99 [9.0])))
      (let [missing (slug/mesh a [{:key :missing}
                                  {:key "rect" :transform [0 0 0]}])]
        (is (= 80 (alength (:vertices missing))))
        (is (= 6 (alength (:indices missing))))))))

(deftest recursive-mesh-composes-transforms-and-rejects-cycles
  (slug/with-atlas [a {:texture-width 128}]
    (slug/add-shape! a :rect rectangle-curves {})
    (slug/build! a)
    (slug/add-composite! a :inner
      [{:key :rect :transform [1 0 0] :scale 1}] {})
    (slug/add-composite! a :outer
      [{:key :rect :transform [0 1 0] :scale 1}
       {:key :inner :transform [2 3 0] :scale 2}] {})
    (let [{:keys [vertices indices]} (slug/mesh a :outer)]
      (is (= 160 (alength vertices)))
      (is (= 12 (alength indices)))
      (is (floats-at vertices 0 [-0.01 0.99 0.0]))
      (is (floats-at vertices 80 [7.98 5.98 0.0])))
    (slug/add-composite! a :cycle-a [{:key :cycle-b}] {})
    (slug/add-composite! a :cycle-b [{:key :cycle-a}] {})
    (slug/add-composite! a :deep [{:key :outer :transform [1 1 0] :scale 3}] {})
    (let [{:keys [vertices]} (slug/mesh a :deep)]
      (is (floats-at vertices 0 [2.97 11.97 0.0]))
      (is (floats-at vertices 80 [77.94 59.94 0.0])))
    (let [error (capture-ex-info #(slug/mesh a :cycle-a))]
      (is (some? error))
      (is (= :cycle-a (:cycle (ex-data error)))))))

(deftest codepoint-composite-keys-and-masks
  (slug/with-atlas [a {:texture-width 128}]
    (let [shape-key [:codepoint 65 7]
          root-key [:codepoint 66 7]]
      (slug/add-shape! a shape-key rectangle-curves {})
      (slug/build! a)
      (slug/add-composite! a root-key [{:key shape-key :transform [1 2 0]}] {})
      (is (= shape-key (get-in (slug/composite a root-key) [:layers 0 :key])))
      (is (= 80 (alength (:vertices (slug/mesh a root-key))))))))

(deftest serialization-round-trips-and-requires-build
  (doseq [extension ["slug" "slugb"]]
    (let [path (str "native/build/task-2-round-trip." extension)]
      (try
        (slug/with-atlas [a {:texture-width 128}]
          (let [error (capture-ex-info #(slug/write! a path))]
            (is (some? error))
            (is (= :write! (:op (ex-data error))))
            (is (re-find #"built" (:native-error (ex-data error)))))
          (slug/add-shape! a :rect rectangle-curves {})
          (slug/add-composite! a :root [{:key :rect :transform [1 2 0]}] {})
          (slug/build! a)
          (let [expected-curve (vec (:bytes (slug/texture a :curve)))
                expected-band (vec (:bytes (slug/texture a :band)))]
            (slug/write! a path)
            (let [loaded (slug/read-atlas path)]
              (try
                (is (true? (slug/built? loaded)))
                (is (= 1.0 (:width (slug/shape loaded :rect))))
                (is (= expected-curve (vec (:bytes (slug/texture loaded :curve)))))
                (is (= expected-band (vec (:bytes (slug/texture loaded :band)))))
                (is (= 80 (alength (:vertices (slug/mesh loaded :root)))))
                (finally
                  (slug/destroy! loaded))))))
        (finally
          (.delete (java.io.File. path)))))))
