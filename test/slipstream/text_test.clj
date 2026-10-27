(ns slipstream.text-test
  (:require [clojure.test :refer [deftest is]]
            [slipstream.core :as slug]))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT to a TrueType/OpenType font before running text tests"
                      {:environment-variable "SLIPSTREAM_TEST_FONT"}))))

(defn- close? [a b]
  (< (abs (- a b)) 0.0001))

(deftest font-loading-returns-metrics-advances-and-empty-whitespace-geometry
  (slug/with-atlas [a {:texture-width 256}]
    (let [{:keys [loaded metrics family style]}
          (slug/load-font! a test-font [65 32 233] {})]
      (is (= 3 loaded))
      (is (seq family))
      (is (seq style))
      (is (pos? (:units-per-em metrics)))
      (is (pos? (:cap-height-ratio metrics)))
      (is (pos? (:x-height-ratio metrics)))
      (let [glyph (slug/shape a [:codepoint 65])
            space (slug/shape a [:codepoint 32])]
        (is (pos? (:advance glyph)))
        (is (pos? (:width glyph)))
        (is (pos? (:height glyph)))
        (is (pos? (:advance space)))
        (is (zero? (:width space)))
        (is (zero? (:height space)))))))

(deftest ascii-and-full-font-loading-forward-mask-and-uniform-options
  (slug/with-atlas [a {:texture-width 256}]
    (let [{:keys [loaded]} (slug/load-ascii-font! a test-font {:mask 7 :uniform true})]
      (is (pos? loaded))
      (is (some? (slug/shape a [:codepoint 65 7])))))
  (slug/with-atlas [a {:texture-width 256}]
    (let [{:keys [loaded metrics]} (slug/load-all-font! a test-font {})]
      (is (pos? loaded))
      (is (pos? (:units-per-em metrics)))
      (is (some? (slug/shape a [:codepoint 233]))))))

(deftest unicode-text-layout-uses-codepoints-and-font-advances
  (slug/with-atlas [a {:texture-width 256}]
    (let [{:keys [metrics]} (slug/load-font! a test-font [65 233] {})
          opts {:font-size 2 :position [4 6] :color [1 0.5 0.25 1]
                :metrics metrics :align :left :anchor :baseline}
          layers (slug/text-layers a "Aé" opts)
          advance (:advance (slug/shape a [:codepoint 65]))]
      (is (= [[:codepoint 65] [:codepoint 233]] (mapv :key layers)))
      (is (= [2.0 2.0] (mapv :scale layers)))
      (is (= [1.0 0.5 0.25 1.0] (:color (first layers))))
      (is (= [2.0 3.0 0.0] (:transform (first layers))))
      (is (close? (+ 2 advance) (first (:transform (second layers))))))))

(deftest compatibility-layout-deliberately-uses-each-utf8-byte
  (slug/with-atlas [a {:texture-width 256}]
    (let [{:keys [metrics]} (slug/load-font! a test-font [65 195 169 233] {})
          opts {:font-size 1 :position [0 0] :metrics metrics}
          unicode (slug/text-layers a "Aé" opts)
          compatible (slug/text-layers a "Aé" (assoc opts :compatibility true))]
      (is (= [[:codepoint 65] [:codepoint 233]] (mapv :key unicode)))
      (is (= [[:codepoint 65] [:codepoint 195] [:codepoint 169]]
             (mapv :key compatible)))
      (is (not= (mapv :key unicode) (mapv :key compatible)))
      (slug/with-canvas [c a {}]
        (slug/layout-text! c a "Aé" (assoc opts :compatibility true))
        (slug/finalize! c :bytes)
        (is (= compatible (:layers (slug/composite a :bytes))))))))

(deftest canvas-layout-uses-the-real-bytewise-canvas-text-path
  (slug/with-atlas [a {:texture-width 256}]
    (let [{:keys [metrics]} (slug/load-font! a test-font [195 169] {})]
      (slug/with-canvas [c a {}]
        (slug/layout-text! c a "é" {:font-size 1 :metrics metrics})
        (slug/finalize! c :canvas-text)
        (is (= [[:codepoint 195] [:codepoint 169]]
               (mapv :key (:layers (slug/composite a :canvas-text)))))))))

(deftest masked-text-layers-preserve-the-glyph-key-namespace
  (slug/with-atlas [a {:texture-width 256}]
    (let [{:keys [metrics]} (slug/load-font! a test-font [65] {:mask 7})]
      (is (= [[:codepoint 65 7]]
             (mapv :key (slug/text-layers a "A" {:mask 7 :metrics metrics})))))))

(deftest supplementary-characters-produce-one-codepoint-layer
  (slug/with-atlas [a {:texture-width 256}]
    (let [layers (slug/text-layers a "😀" {})]
      (is (= 1 (count layers)))
      (is (= [[:codepoint 0x1f600]] (mapv :key layers))))))
