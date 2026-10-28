(ns slipstream.glfw-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [slipstream.core :as slug]
            [slipstream.example.glfw :as example]
            [slipstream.ffi :as f]
            [slipstream.glfw :as glfw]))

(def ^:private expected-usage
  (str "Usage: slipstream.example.glfw <font.ttf> [--curve-precision 16|32]\n"
       "  --curve-precision: curve texture float precision (DEFAULT 32). 16 halves\n"
       "    curve-texture memory but is a real precision tradeoff -- can visibly\n"
       "    degrade shape edges under heavy zoom.\n"))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT for the GLFW example tests" {}))))

(deftest example-arguments-match-the-upstream-command-contract
  (is (= {:font-path "/tmp/font.ttf" :curve-precision 32
          :capture-path nil :max-frames nil}
         (example/parse-args ["/tmp/font.ttf"])))
  (is (= {:font-path "/tmp/font.ttf" :curve-precision 16
          :capture-path "/tmp/frame.ppm" :max-frames 1}
         (example/parse-args ["/tmp/font.ttf" "--curve-precision" "16"
                              "--frames" "1" "--capture" "/tmp/frame.ppm"])))
  (is (= {:error expected-usage} (example/parse-args [])))
  (is (= {:error "Invalid --curve-precision: 8 (expected 16 or 32)\n"}
         (example/parse-args ["/tmp/font.ttf" "--curve-precision" "8"]))))

(deftest example-selects-default-or-trimmed-piped-text
  (is (= "slughorn" (example/select-text nil)))
  (is (= "slughorn" (example/select-text "\r\n")))
  (is (= "slughorn" (example/select-text "slughorn")))
  (is (= "kK" (example/select-text "kK\r\n"))))

(deftest example-scene-preserves-upstream-byte-wise-text-layout
  (slug/with-scene [scene (example/compile-scene test-font "Aé" 32)]
    (is (= [[:codepoint 65] [:codepoint 195] [:codepoint 169]]
           (mapv :key (:layers scene))))))

(deftest upstream-layout-skips-missing-and-zero-area-glyphs
  (slug/with-atlas [atlas {:texture-width 256}]
    (slug/load-font! atlas test-font [65 32] {})
    (let [missing-result (example/upstream-text-layers atlas "BA")
          space-advance (:advance (slug/shape atlas [:codepoint 32]))
          space-result (example/upstream-text-layers atlas " A")]
      (is (= [[:codepoint 65]] (mapv :key missing-result)))
      (is (= [0.5 0 0] (:transform (first missing-result))))
      (is (= [[:codepoint 65]] (mapv :key space-result)))
      (is (= [space-advance 0 0] (:transform (first space-result)))))))

(deftest zero-area-only-example-input-fails-before-opening-a-window
  (slug/with-scene [scene (example/compile-scene test-font " " 32)]
    (is (empty? (:layers scene)))
    (let [error (try
                  (glfw/run! scene {:max-frames 1})
                  nil
                  (catch Throwable error error))]
      (is (some? error))
      (is (re-find #"No geometry produced" (:native-error (ex-data error)))))))

(deftest compatibility-renderer-supports-nondefault-atlas-width
  (glfw/run! [:scene {:texture-width 256}
              [:shape {} [:rect 0 0 1 1]]]
             {:max-frames 1}))

(deftest renderer-native-symbols-resolve-and-reject-a-null-atlas
  (ffi/with-layout [view f/layers-view-layout]
    (is (not (zero? (f/glfw-run 0 view 0 32 0 0))))
    (is (re-find #"atlas" (f/last-error))))
  (let [text-p (f/read-stdin-text)]
    (when (and text-p (not (zero? text-p)))
      (is (string? (ffi/ptr->string text-p)))
      (f/free-stdin-text text-p))))
