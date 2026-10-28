(ns slipstream.glimmer-resources-test
  (:require [clojure.test :refer [deftest is testing]]
            [slipstream.core :as slug]
            [slipstream.glimmer-compiler :as compiler]
            [slipstream.glimmer-resources :as resources]))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT before running resource tests"
                      {:environment-variable "SLIPSTREAM_TEST_FONT"}))))

(defn- shape-props []
  {:path [[:rect 0 0 1 1]]
   :fill [1 0 0 1]
   :texture-width 128})

(defn- widget [tag props resource]
  (atom {:tag tag :props props :resource resource :resources nil
         :children [] :disposed? false}))

(defn- texture-snapshot [atlas kind]
  (let [texture (slug/texture atlas kind)]
    (assoc texture :bytes (vec (:bytes texture)))))

(defn- atlas-snapshot [resource]
  {:curve (texture-snapshot (:atlas resource) :curve)
   :band (texture-snapshot (:atlas resource) :band)})

(defn- changed-references [result]
  [(vec (:acquired result)) (vec (:released result))])

(deftest geometry-keys-include-native-inputs-and-exclude-layer-only-props
  (let [flat (shape-props)]
    (is (= (compiler/geometry-key flat)
           (compiler/geometry-key
             (assoc flat :color [0 1 0 1] :at [9 8] :transform [4 5 6]
                         :visible false :effect-id 7 :effect-param 0.5
                         :on-click (fn [_] :changed)
                         :fill [0 0 1 1]))))
    (doseq [changed [(assoc flat :path [[:rect 0 0 2 1]])
                     (assoc flat :stroke 0.2)
                     (assoc flat :join :round)
                     (assoc flat :cap :square)
                     (assoc flat :miter-limit 8)
                     (assoc flat :scale 2)
                     (assoc flat :origin :centered)
                     (assoc flat :splits-x [0.5])
                     (assoc flat :splits-y [0.25 0.75])
                     (assoc flat :sdf true)
                     (assoc flat :sdf-range 0.4)
                     (assoc flat :auto-metrics false)
                     (assoc flat :clockwise true)
                     (assoc flat :texture-width 256)
                     (assoc flat :curve-precision 16)]]
      (is (not= (compiler/geometry-key flat) (compiler/geometry-key changed))
          (pr-str changed))))
  (testing "every gradient and pattern input participates in dependency identity"
    (let [gradient (assoc (shape-props)
                          :fill {:type :linear :from [0 0] :to [1 0]
                                 :stops [[0 [1 0 0 1]] [1 [0 0 1 1]]]})
          pattern (assoc (shape-props)
                         :fill {:type :pattern :tile :tile
                                :tile-path [[:rect 0 0 1 1]]
                                :cell [0 0 1 1]
                                :transform [1 0 0 1 0 0]
                                :repeat-x :repeat :repeat-y :reflect})]
      (doseq [changed [(assoc-in gradient [:fill :from] [0 1])
                       (assoc-in gradient [:fill :to] [2 0])
                       (assoc-in gradient [:fill :stops] [[0 [1 1 0 1]]])
                       (assoc-in gradient [:fill :type] :sweep)
                       (assoc-in gradient [:fill :center] [0.5 0.5])
                       (assoc-in gradient [:fill :inner-radius] 0.2)
                       (assoc-in gradient [:fill :outer-radius] 2)
                       (assoc-in gradient [:fill :start-angle] 0.1)
                       (assoc-in gradient [:fill :end-angle] 0.9)
                       (assoc-in pattern [:fill :tile] :other)
                       (assoc-in pattern [:fill :tile-path] [[:rect 0 0 2 2]])
                       (assoc-in pattern [:fill :cell] [0 0 2 2])
                       (assoc-in pattern [:fill :transform] [2 0 0 1 0 0])
                       (assoc-in pattern [:fill :repeat-x] :reflect)
                       (assoc-in pattern [:fill :repeat-y] :repeat)]]
        (is (not= (compiler/geometry-key (if (= :pattern (get-in changed [:fill :type]))
                                           pattern gradient))
                  (compiler/geometry-key changed))
            (pr-str changed))))))

(deftest identical-shapes-share-built-cpu-data-and-reference-counts
  (let [first-resource (compiler/compile-shape (shape-props))
        second-resource (compiler/compile-shape (shape-props))]
    (try
      (is (identical? first-resource second-resource))
      (is (= 2 @(:ref-count first-resource)))
      (is (true? (slug/built? (:atlas first-resource))))
      (is (map? (:root first-resource)))
      (is (= 80 (:stride (:mesh first-resource))))
      (is (= #{:atlas :root :mesh :cache-key :ref-count}
             (set (keys first-resource))))
      (resources/release! first-resource)
      (is (= 1 @(:ref-count second-resource)))
      (is (true? (slug/built? (:atlas second-resource))))
      (finally
        (resources/release! second-resource)))
    (is (zero? @(:ref-count first-resource)))
    (is @(:destroyed? (:atlas first-resource)))
    (let [replacement (compiler/compile-shape (shape-props))]
      (is (not (identical? first-resource replacement)))
      (resources/release! replacement))))

(deftest layer-only-and-order-updates-do-not-acquire-or-release-resources
  (let [resource (compiler/compile-shape (shape-props))
        old-props (assoc (shape-props) :color [1 0 0 1] :at [0 0])
        node (widget :shape old-props resource)
        new-props (assoc old-props :color [0 1 0 1] :at [10 20]
                         :children [[:shape {}] [:text {}]])]
    (try
      (let [result (compiler/update-node! node old-props new-props)]
        (is (= [[] []] (changed-references result)))
        (is (identical? resource (:resource @node)))
        (is (= 1 @(:ref-count resource)))
        (is (= new-props (:props @node))))
      (finally
        (resources/dispose-tree! node)))))

(deftest named-flat-paint-color-updates-do-not-recompile-geometry
  (let [old-props (assoc (shape-props) :fill :main :paints {:main [1 0 0 1]})
        resource (compiler/compile-shape old-props)
        node (widget :shape old-props resource)
        new-props (assoc old-props :paints {:main [0 0 1 1]})]
    (try
      (let [result (compiler/update-node! node old-props new-props)]
        (is (= [[] []] (changed-references result)))
        (is (identical? resource (:resource @node)))
        (is (= 1 @(:ref-count resource))))
      (finally
        (resources/dispose-tree! node)
        (resources/release! resource)))))

(deftest shape-replacement-preserves-unrelated-atlas-descriptors-and-bytes
  (let [old-a-props (shape-props)
        old-b-props (assoc (shape-props) :path [[:circle 0.5 0.5 0.4]])
        old-a (compiler/compile-shape old-a-props)
        old-b (compiler/compile-shape old-b-props)
        a (widget :shape old-a-props old-a)
        b (widget :shape old-b-props old-b)
        before-b (atlas-snapshot old-b)
        new-a-props (assoc old-a-props :path [[:rect 0 0 2 1]] :stroke 0.1)]
    (try
      (let [result (compiler/update-node! a old-a-props new-a-props)
            new-a (:resource @a)]
        (is (= [[new-a] [old-a]] (changed-references result)))
        (is (not (identical? old-a new-a)))
        (is @(:destroyed? (:atlas old-a)))
        (is (identical? old-b (:resource @b)))
        (is (= 1 @(:ref-count old-b)))
        (is (= before-b (atlas-snapshot old-b)))
        (resources/dispose-tree! a))
      (finally
        (resources/dispose-tree! b)
        (resources/release! old-a)))))

(deftest text-edits-acquire-only-added-glyphs-and-release-only-removed-glyphs
  (let [old-props {:font test-font :text "AB" :texture-width 128}
        old-a (compiler/compile-glyph test-font 65 old-props)
        old-b (compiler/compile-glyph test-font 66 old-props)
        node (atom {:tag :text :props old-props :resource nil
                    :resources [old-a old-b] :children [] :disposed? false})
        new-props (assoc old-props :text "BC")]
    (try
      (let [result (compiler/update-node! node old-props new-props)
            new-c (second (:resources @node))]
        (is (= [[:codepoint 66] [:codepoint 67]]
               (mapv #(get-in % [:root :key]) (:resources @node))))
        (is (identical? old-b (first (:resources @node))))
        (is (= [[new-c] [old-a]] (changed-references result)))
        (is (= 1 @(:ref-count old-b)))
        (is @(:destroyed? (:atlas old-a)))
        (resources/dispose-tree! node)
        (is @(:destroyed? (:atlas old-b)))
        (is @(:destroyed? (:atlas new-c))))
      (finally
        (resources/dispose-tree! node)
        (resources/release! old-a)))))

(deftest text-release-failure-does-not-release-installed-glyphs
  (let [old-props {:font test-font :text "AB" :texture-width 128}
        old-a (compiler/compile-glyph test-font 65 old-props)
        old-b (compiler/compile-glyph test-font 66 old-props)
        node (atom {:tag :text :props old-props :resource nil
                    :resources [old-a old-b] :children [] :disposed? false})
        new-props (assoc old-props :text "BC")
        original-release resources/release!]
    (try
      (with-redefs [resources/release!
                    (fn [resource]
                      (if (identical? resource old-a)
                        (throw (ex-info "injected glyph release failure" {}))
                        (original-release resource)))]
        (is (thrown-with-msg? Exception #"injected glyph release failure"
                              (compiler/update-node! node old-props new-props)))
        (let [new-c (second (:resources @node))]
          (is (= [[:codepoint 66] [:codepoint 67]]
                 (mapv #(get-in % [:root :key]) (:resources @node))))
          (is (= new-props (:props @node)))
          (is (= 1 @(:ref-count new-c)))
          (is (true? (slug/built? (:atlas new-c))))
          (is (= 1 @(:ref-count old-a)))))
      (finally
        (resources/dispose-tree! node)
        (resources/release! old-a)))))

(deftest duplicate-glyphs-have-independent-ownership-of-one-cache-entry
  (let [old-props {:font test-font :text "AA" :texture-width 128}
        first-a (compiler/compile-glyph test-font 65 old-props)
        second-a (compiler/compile-glyph test-font 65 old-props)
        node (atom {:tag :text :props old-props :resource nil
                    :resources [first-a second-a] :children [] :disposed? false})]
    (is (identical? first-a second-a))
    (is (= 2 @(:ref-count first-a)))
    (compiler/update-node! node old-props (assoc old-props :text "A"))
    (is (= [first-a] (:resources @node)))
    (is (= 1 @(:ref-count first-a)))
    (is (true? (slug/built? (:atlas first-a))))
    (resources/dispose-tree! node)
    (is (zero? @(:ref-count first-a)))
    (is @(:destroyed? (:atlas first-a)))))

(deftest glyph-cache-key-covers-font-configuration
  (let [plain (compiler/compile-glyph test-font 65 {:texture-width 128})
        duplicate (compiler/compile-glyph test-font 65 {:texture-width 128})
        masked (compiler/compile-glyph test-font 65 {:texture-width 128 :mask 3})]
    (try
      (is (identical? plain duplicate))
      (is (= 2 @(:ref-count plain)))
      (is (not (identical? plain masked)))
      (is (= [:codepoint 65] (get-in plain [:root :key])))
      (is (= [:codepoint 65 3] (get-in masked [:root :key])))
      (finally
        (resources/release! plain)
        (resources/release! duplicate)
        (resources/release! masked)))))

(deftest failed-shape-replacement-keeps-the-last-good-resource
  (let [old-props (shape-props)
        old-resource (compiler/compile-shape old-props)
        node (widget :shape old-props old-resource)
        new-props (assoc old-props :path [[:not-a-path-command 1 2]])]
    (is (thrown? Exception
                 (compiler/update-node! node old-props new-props)))
    (is (identical? old-resource (:resource @node)))
    (is (= old-props (:props @node)))
    (is (= 1 @(:ref-count old-resource)))
    (is (true? (slug/built? (:atlas old-resource))))
    (resources/dispose-tree! node)))
