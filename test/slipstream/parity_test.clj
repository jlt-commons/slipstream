(ns slipstream.parity-test
  (:require [clojure.test :as t :refer [deftest is]]
            [slipstream.core :as slug]
            [slipstream.example.glfw :as example])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files OpenOption]))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT for the native parity tests" {}))))

(defn- absolute-path [path]
  (.getAbsolutePath (java.io.File. (str path))))

(def ^:private oracle-path
  (absolute-path "native/build/slipstream-parity-oracle"))

(def ^:private texture-formats
  [:rgba32f :rgba16f :rgba16ui :rg16ui :rgba8 :rgb32f :r32f])

(defn- nio-path [path]
  (java.nio.file.Paths/get (str path) (make-array String 0)))

(defn- create-directories! [path]
  (Files/createDirectories (nio-path path)
                           (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- delete-file! [path]
  (Files/deleteIfExists (nio-path path)))

(defn- write-bytes! [path bytes]
  (Files/write (nio-path path) bytes (make-array OpenOption 0)))

(defn- write-floats! [path values]
  (let [buffer (ByteBuffer/allocate (* 4 (alength values)))]
    (.order buffer (ByteOrder/nativeOrder))
    (doseq [value values] (.putFloat buffer (float value)))
    (write-bytes! path (.array buffer))))

(defn- write-uint16s! [path values]
  (let [buffer (ByteBuffer/allocate (* 2 (alength values)))]
    (.order buffer (ByteOrder/nativeOrder))
    (doseq [value values] (.putShort buffer (short value)))
    (write-bytes! path (.array buffer))))

(defn- write-texture! [directory kind texture]
  (spit (str directory "/" kind ".desc")
        (format "%d %d %d %d %d\n"
                (:width texture) (:height texture) (:depth texture)
                (.indexOf texture-formats (:format texture)) (:byte-count texture)))
  (write-bytes! (str directory "/" kind ".bin") (:bytes texture)))

(defn- write-jolt-data! [directory text precision]
  (create-directories! directory)
  (slug/with-scene [scene (example/compile-scene test-font text precision)]
    (write-texture! directory "curve" (slug/texture scene :curve))
    (write-texture! directory "band" (slug/texture scene :band))
    (let [mesh (slug/mesh scene)
          vertex-count (/ (alength (:vertices mesh)) 20)]
      (spit (str directory "/mesh.desc")
            (format "%d %d %d\n" vertex-count (alength (:indices mesh)) (:stride mesh)))
      (write-floats! (str directory "/vertices.bin") (:vertices mesh))
      (write-uint16s! (str directory "/indices.bin") (:indices mesh)))))

(defn- run-oracle! [directory text precision]
  (create-directories! directory)
  (let [builder (ProcessBuilder.
                  (into-array String [oracle-path "--data" test-font (str precision)
                                      text (str directory)]))]
    (.inheritIO builder)
    (let [exit (.waitFor (.start builder))]
      (when-not (zero? exit)
        (throw (ex-info "C++ parity oracle failed" {:exit exit}))))))

(defn- assert-file= [expected-path actual-path]
  (let [expected (Files/readAllBytes (nio-path expected-path))
        actual (Files/readAllBytes (nio-path actual-path))]
    (is (java.util.Arrays/equals expected actual)
        (str actual-path " differs from " expected-path))))

(deftest native-data-matches-byte-for-byte
  (doseq [[label text] [["default" "slughorn"] ["piped" "kK"]]
          precision [16 32]]
    (let [case-root (absolute-path (str "native/build/parity/" label "-" precision))
          cpp-directory (str case-root "/cpp")
          jolt-directory (str case-root "/jolt")]
      (run-oracle! cpp-directory text precision)
      (write-jolt-data! jolt-directory text precision)
      (doseq [filename ["curve.desc" "curve.bin" "band.desc" "band.bin"
                        "mesh.desc" "vertices.bin" "indices.bin"]]
        (assert-file= (str cpp-directory "/" filename)
                      (str jolt-directory "/" filename))))))

(def ^:private upstream-path
  (absolute-path "native/build/slughorn-example-glfw"))

(def ^:private controls-text
  (str "Controls:\n"
       "  Left drag   orbit\n"
       "  Right drag  pan\n"
       "  Wheel       zoom\n"
       "  P           toggle perspective/ortho\n"
       "  R           reset view\n"
       "  Esc         quit\n"))

(defn- check! [value message]
  (when-not value (throw (ex-info message {}))))

(def ^:private initial-frame-header (.getBytes "P6\n800 600\n255\n" "UTF-8"))
(def ^:private initial-frame-payload-size (* 800 600 3))

(defn- validate-initial-frame! [frame label]
  (let [header-size (alength initial-frame-header)
        expected-size (+ header-size initial-frame-payload-size)
        actual-size (alength frame)]
    (check! (= expected-size actual-size)
            (str label " must have an exact 800x600 RGB payload; expected "
                 expected-size " bytes, got " actual-size))
    (check! (every? #(= (aget initial-frame-header %) (aget frame %))
                    (range header-size))
            (str label " must have the exact 800x600 PPM header"))
    (let [red (aget frame header-size)
          green (aget frame (inc header-size))
          blue (aget frame (+ header-size 2))
          nonblank (loop [offset (+ header-size 3)]
                     (cond
                       (= offset actual-size) false
                       (or (not= red (aget frame offset))
                           (not= green (aget frame (inc offset)))
                           (not= blue (aget frame (+ offset 2)))) true
                       :else (recur (+ offset 3))))]
      (check! nonblank (str label " must contain nonblank rendered content"))))
  nil)

(deftest initial-frame-validation-rejects-invalid-captures
  (let [header (.getBytes "P6\n800 600\n255\n" "UTF-8")
        header-size (alength header)
        payload-size (* 800 600 3)
        valid (byte-array (+ header-size payload-size))
        wrong-size (byte-array (+ header-size payload-size))
        truncated (byte-array (dec (+ header-size payload-size)))
        blank (byte-array (+ header-size payload-size))
        uniform-gray (byte-array (+ header-size payload-size) (byte 38))]
    (doseq [frame [valid wrong-size truncated blank uniform-gray]]
      (System/arraycopy header 0 frame 0 header-size))
    (System/arraycopy (.getBytes "P6\n799 600\n255\n" "UTF-8") 0 wrong-size 0 header-size)
    (aset valid header-size (byte 1))
    (is (nil? (validate-initial-frame! valid "valid frame")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"800x600 PPM header"
                          (validate-initial-frame! wrong-size "wrong size")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"800x600 RGB payload"
                          (validate-initial-frame! truncated "truncated")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nonblank"
                          (validate-initial-frame! blank "blank")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nonblank"
                          (validate-initial-frame! uniform-gray "uniform gray")))))

(defn- check-file= [expected-path actual-path]
  (let [expected (Files/readAllBytes (nio-path expected-path))
        actual (Files/readAllBytes (nio-path actual-path))]
    (check! (java.util.Arrays/equals expected actual)
            (str actual-path " differs from " expected-path))))

(defn- run-process [label arguments {:keys [input env] :or {input nil env {}}}]
  (let [directory (absolute-path "native/build/parity/process")]
    (create-directories! directory)
    (let [output-path (str directory "/" label ".out")
          error-path (str directory "/" label ".err")
          input-path (if input (str directory "/" label ".in") "/dev/null")
          builder (ProcessBuilder. (into-array String (map str arguments)))]
      (when input (spit input-path input))
      (.redirectInput builder (java.io.File. input-path))
      (.redirectOutput builder (java.io.File. output-path))
      (.redirectError builder (java.io.File. error-path))
      (doseq [[key value] env] (.put (.environment builder) key value))
      (let [process (.start builder)]
        (when-not (.waitFor process 90 java.util.concurrent.TimeUnit/SECONDS)
          (.destroyForcibly process)
          (.waitFor process)
          (throw (ex-info (str "process timed out: " label) {})))
        {:exit (.exitValue process)
         :out (slurp output-path)
         :err (slurp error-path)}))))

(defn- jolt-arguments [font-args]
  (into ["jolt" "-m" "slipstream.example.glfw"] font-args))

(defn- compare-command-contract! []
  (let [cpp (run-process "usage-cpp" [upstream-path] {})
        jolt (run-process "usage-jolt" (jolt-arguments []) {})
        expected-cpp (.replace example/usage-text "slipstream.example.glfw" upstream-path)]
    (check! (= 1 (:exit cpp) (:exit jolt)) "usage commands must exit 1")
    (check! (= expected-cpp (:err cpp)) "upstream usage output differs")
    (check! (= example/usage-text (:err jolt)) "Jolt usage output differs"))
  (let [args [test-font "--curve-precision" "8"]
        cpp (run-process "invalid-cpp" (into [upstream-path] args) {})
        jolt (run-process "invalid-jolt" (jolt-arguments args) {})]
    (check! (= 1 (:exit cpp) (:exit jolt)) "invalid precision commands must exit 1")
    (check! (= "Invalid --curve-precision: 8 (expected 16 or 32)\n"
               (:err cpp) (:err jolt))
            "invalid precision output differs"))
  (println "PASS usage and invalid-argument behavior"))

(defn- compare-initial-frame! [label text precision]
  (let [root (absolute-path (str "native/build/parity/visual-" label))
        cpp-capture (str root "/cpp.ppm")
        jolt-capture (str root "/jolt.ppm")
        _ (create-directories! root)
        _ (doseq [path [cpp-capture jolt-capture]] (delete-file! path))
        font-args [test-font "--curve-precision" (str precision)]
        cpp (run-process (str "initial-" label "-cpp")
                         (into [oracle-path] font-args)
                         {:input text
                          :env {"SLIPSTREAM_PARITY_CAPTURE" cpp-capture
                                "SLIPSTREAM_PARITY_MAX_FRAMES" "1"}})
        jolt (run-process (str "initial-" label "-jolt")
                          (jolt-arguments (into font-args
                                                ["--frames" "1" "--capture" jolt-capture]))
                          {:input text})]
    (check! (zero? (:exit cpp)) (str "upstream GUI failed: " (:err cpp)))
    (check! (zero? (:exit jolt)) (str "Jolt GUI failed: " (:err jolt)))
    (check! (.startsWith (:out cpp) controls-text) "upstream controls output differs")
    (check! (.startsWith (:out jolt) controls-text) "Jolt controls output differs")
    (check! (= (:out cpp) (:out jolt)) "controls/OpenGL output differs")
    (check! (= (:err cpp) (:err jolt)) "GUI stderr differs")
    (validate-initial-frame! (Files/readAllBytes (nio-path cpp-capture))
                             "upstream initial frame")
    (validate-initial-frame! (Files/readAllBytes (nio-path jolt-capture))
                             "Jolt initial frame")
    (check-file= cpp-capture jolt-capture)
    (println (str "PASS initial 800x600 frame and clean exit: " label))))

(defn- compare-control-sequence! []
  (let [root (absolute-path "native/build/parity/visual-controls")
        cpp-directory (str root "/cpp")
        jolt-directory (str root "/jolt")
        _ (create-directories! cpp-directory)
        _ (create-directories! jolt-directory)
        _ (doseq [directory [cpp-directory jolt-directory]
                  frame (range 7)]
            (delete-file! (str directory "/" frame ".ppm")))
        font-args [test-font "--curve-precision" "32"]
        cpp-env {"SLIPSTREAM_PARITY_CAPTURE" (str cpp-directory "/first.ppm")
                 "SLIPSTREAM_PARITY_CAPTURE_DIR" cpp-directory
                 "SLIPSTREAM_PARITY_CONTROLS" "1"
                 "SLIPSTREAM_PARITY_MAX_FRAMES" "7"}
        cpp (run-process "controls-cpp" (into [oracle-path] font-args) {:env cpp-env})
        jolt (run-process "controls-jolt"
                          (jolt-arguments
                            (into font-args
                                  ["--frames" "7" "--capture" (str jolt-directory "/first.ppm")]))
                          {:env {"SLIPSTREAM_PARITY_CAPTURE_DIR" jolt-directory
                                 "SLIPSTREAM_PARITY_CONTROLS" "1"}})]
    (check! (zero? (:exit cpp)) (str "upstream control sequence failed: " (:err cpp)))
    (check! (zero? (:exit jolt)) (str "Jolt control sequence failed: " (:err jolt)))
    (check! (= (:out cpp) (:out jolt)) "control-sequence output differs")
    (doseq [frame (range 7)]
      (check-file= (str cpp-directory "/" frame ".ppm")
                   (str jolt-directory "/" frame ".ppm")))
    (check-file= (str cpp-directory "/0.ppm") (str cpp-directory "/2.ppm"))
    (check-file= (str cpp-directory "/0.ppm") (str cpp-directory "/6.ppm"))
    (check! (not (java.util.Arrays/equals
                   (Files/readAllBytes (nio-path (str cpp-directory "/0.ppm")))
                   (Files/readAllBytes (nio-path (str cpp-directory "/1.ppm")))))
            "projection toggle did not change the upstream frame")
    (check! (not (java.util.Arrays/equals
                   (Files/readAllBytes (nio-path (str cpp-directory "/0.ppm")))
                   (Files/readAllBytes (nio-path (str cpp-directory "/3.ppm")))))
            "orbit did not change the upstream frame")
    (check! (not (java.util.Arrays/equals
                   (Files/readAllBytes (nio-path (str cpp-directory "/3.ppm")))
                   (Files/readAllBytes (nio-path (str cpp-directory "/4.ppm")))))
            "pan did not change the upstream frame")
    (check! (not (java.util.Arrays/equals
                   (Files/readAllBytes (nio-path (str cpp-directory "/4.ppm")))
                   (Files/readAllBytes (nio-path (str cpp-directory "/5.ppm")))))
            "zoom did not change the upstream frame")
    (println "PASS projection toggle/reset, orbit, pan, zoom, reset, and clean exit")))

(defn- compare-scroll-injection! []
  (let [root (absolute-path "native/build/parity/visual-scroll-injection")
        cpp-baseline (str root "/cpp-baseline.ppm")
        jolt-baseline (str root "/jolt-baseline.ppm")
        cpp-injected (str root "/cpp-injected.ppm")
        jolt-injected (str root "/jolt-injected.ppm")
        _ (create-directories! root)
        _ (doseq [path [cpp-baseline jolt-baseline cpp-injected jolt-injected]]
            (delete-file! path))
        font-args [test-font "--curve-precision" "32"]
        cpp (run-process "scroll-baseline-cpp" (into [oracle-path] font-args)
                         {:env {"SLIPSTREAM_PARITY_CAPTURE" cpp-baseline
                                "SLIPSTREAM_PARITY_MAX_FRAMES" "1"}})
        jolt (run-process "scroll-baseline-jolt"
                          (jolt-arguments
                            (into font-args ["--frames" "1" "--capture" jolt-baseline]))
                          {})]
    (check! (zero? (:exit cpp)) (str "upstream scroll baseline failed: " (:err cpp)))
    (check! (zero? (:exit jolt)) (str "Jolt scroll baseline failed: " (:err jolt)))
    (check-file= cpp-baseline jolt-baseline)
    (let [cpp (run-process "scroll-noncapture-cpp" (into [oracle-path] font-args)
                           {:env {"SLIPSTREAM_PARITY_MAX_FRAMES" "1"
                                  "SLIPSTREAM_PARITY_INJECT_SCROLL" "1"}})
          jolt (run-process "scroll-noncapture-jolt"
                            (jolt-arguments (into font-args ["--frames" "1"]))
                            {:env {"SLIPSTREAM_PARITY_INJECT_SCROLL" "1"}})]
      (check! (zero? (:exit cpp)) (str "upstream non-capture scroll failed: " (:err cpp)))
      (check! (zero? (:exit jolt)) (str "Jolt non-capture scroll failed: " (:err jolt)))
      (check! (.contains (:err cpp) "SLIPSTREAM_PARITY_SCROLL_CALLBACK")
              "maxFrames alone must not suppress the upstream physical scroll callback")
      (check! (.contains (:err jolt) "SLIPSTREAM_PARITY_SCROLL_CALLBACK")
              "--frames alone must not suppress the Jolt physical scroll callback"))
    (let [cpp (run-process "scroll-injected-cpp" (into [oracle-path] font-args)
                           {:env {"SLIPSTREAM_PARITY_CAPTURE" cpp-injected
                                  "SLIPSTREAM_PARITY_MAX_FRAMES" "1"
                                  "SLIPSTREAM_PARITY_INJECT_SCROLL" "1"}})
          jolt (run-process "scroll-injected-jolt"
                            (jolt-arguments
                              (into font-args ["--frames" "1" "--capture" jolt-injected]))
                            {:env {"SLIPSTREAM_PARITY_INJECT_SCROLL" "1"}})]
      (check! (zero? (:exit cpp)) (str "upstream injected capture failed: " (:err cpp)))
      (check! (zero? (:exit jolt)) (str "Jolt injected capture failed: " (:err jolt)))
      (check-file= jolt-baseline jolt-injected)
      (check-file= cpp-baseline cpp-injected)
      (check-file= cpp-injected jolt-injected))
    (println "PASS capture ignores physical scroll; non-capture scroll remains active")))

(defn -main [& [focus]]
  (if (= focus "scroll-injection")
    (do
      (compare-scroll-injection!)
      (System/exit 0))
    (let [{:keys [fail error]} (t/run-tests 'slipstream.parity-test)]
      (when (zero? (+ fail error))
        (compare-command-contract!)
        (compare-initial-frame! "default-32" nil 32)
        (compare-initial-frame! "piped-16" "kK\n" 16)
        (compare-control-sequence!)
        (compare-scroll-injection!)
        (println "All native data, visual, and behavior parity checks passed."))
      (System/exit (if (zero? (+ fail error)) 0 1)))))
