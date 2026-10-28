(ns slipstream.glfw-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [slipstream.core :as slug]
            [slipstream.example.glfw :as example]
            [slipstream.ffi :as f]))

(def ^:private expected-usage
  (str "Usage: slipstream.example.glfw <font.ttf> [--curve-precision 16|32]\n"
       "  --curve-precision: curve texture float precision (DEFAULT 32). 16 halves\n"
       "    curve-texture memory but is a real precision tradeoff -- can visibly\n"
       "    degrade shape edges under heavy zoom.\n"))

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
  (let [font (or (System/getenv "SLIPSTREAM_TEST_FONT")
                 (throw (ex-info "Set SLIPSTREAM_TEST_FONT for the example scene test" {})))]
    (slug/with-scene [scene (example/scene-data font "Aé" 32)]
      (is (= [[:codepoint 65] [:codepoint 195] [:codepoint 169]]
             (mapv :key (:layers scene)))))))

(deftest renderer-native-symbols-resolve-and-reject-a-null-atlas
  (ffi/with-layout [view f/layers-view-layout]
    (is (not (zero? (f/glfw-run 0 view 0 32 0 0))))
    (is (re-find #"atlas" (f/last-error))))
  (let [text-p (f/read-stdin-text)]
    (when (and text-p (not (zero? text-p)))
      (is (string? (ffi/ptr->string text-p)))
      (f/free-stdin-text text-p))))
