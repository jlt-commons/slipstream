(ns slipstream.canvas-test
  (:require [clojure.test :refer [deftest is]]
            [slipstream.core :as slug]))

(defn- capture-ex-info [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      e)))

(defn- close? [a b]
  (< (abs (- a b)) 0.0001))

(def ^:private rectangle-curves
  [[0 0 0.5 0 1 0]
   [1 0 1 0.5 1 1]
   [1 1 0.5 1 0 1]
   [0 1 0 0.5 0 0]])

(def ^:private authoring-commands
  [[:move-to 0 0]
   [:line-to 0.2 0]
   [:quad-to 0.3 0.1 0.4 0]
   [:bezier-to 0.5 0.1 0.6 -0.1 0.7 0]
   [:close]
   [:rect 0 0 0.2 0.2]
   [:rounded-rect 0.3 0.3 0.2 0.2 0.05]
   [:circle 0.8 0.8 0.1]
   [:ellipse 0.8 0.2 0.1 0.05]
   [:arc 0.2 0.8 0.1 0 1.5707963267948966]
   [:move-to 0.5 0.5]
   [:arc-to 0.6 0.5 0.6 0.6 0.05]])

(def ^:private transform-commands
  [[:save]
   [:translate 0 0]
   [:rotate 0]
   [:scale 1 1]
   [:transform 1 0 0 1 0 0]
   [:set-transform 1 0 0 1 0 0]
   [:reset-transform]
   [:restore]])

(deftest path-supports-every-authoring-verb-and-sampling
  (slug/with-atlas [a {:texture-width 128}]
    (slug/with-path [p]
      (doseq [command (concat authoring-commands transform-commands)]
        (slug/path-command! p command))
      (slug/with-canvas [c a {}]
        (slug/define-shape! c p :all-verbs {})))
    (let [s (slug/shape a :all-verbs)]
      (is (some? s))
      (is (pos? (:width s)))
      (is (pos? (:height s)))))
  (slug/with-path [p]
    (slug/path-command! p [:save])
    (slug/path-command! p [:translate 2 3])
    (slug/path-command! p [:move-to 0 0])
    (slug/path-command! p [:line-to 1 0])
    (slug/path-command! p [:restore])
    (is (close? 1.0 (slug/arc-length p)))
    (let [s (slug/sample p 0.5)]
      (is (close? 2.5 (:x s)))
      (is (close? 3.0 (:y s)))
      (is (close? 0.0 (:angle s)))
      (is (close? 0.5 (:arc-length s))))
    (is (close? 2.0 (:x (slug/sample p -1))))
    (is (close? 3.0 (:x (slug/sample p 2))))))

(deftest paths-can-be-appended-and-stroked-without-changing-the-source
  (slug/with-path [source]
    (doseq [command [[:move-to 0 0] [:line-to 0.2 0] [:line-to 0.2 0.2]]]
      (slug/path-command! source command))
    (slug/with-path [copy]
      (slug/path-command! copy [:add-path source])
      (is (close? 0.4 (slug/arc-length copy))))
    (let [length (slug/arc-length source)]
      (slug/stroke-path! source 0.05 {:join :round :cap :square :miter-limit 4})
      (is (< length (slug/arc-length source))))))

(deftest canvas-forwards-all-path-commands-and-restores-explicit-path-transforms
  (slug/with-atlas [a {:texture-width 128}]
    (slug/with-canvas [c a {}]
      (slug/canvas-command! c [:begin-path])
      (doseq [command (concat authoring-commands transform-commands)]
        (slug/canvas-command! c command))
      (slug/define-shape! c nil :canvas-verbs {})
      (slug/with-path [p]
        (slug/path-command! p [:rect 0 0 1 1])
        (slug/canvas-command! c [:translate 2 3])
        (let [layer (slug/fill! c p {:color [1 0 0 1]} {:key :translated})]
          (is (close? 2.0 (first (:transform layer))))
          (is (close? 3.0 (second (:transform layer)))))
        (slug/canvas-command! c [:save])
        (slug/canvas-command! c [:translate 5 7])
        (slug/fill! c p {:color [1 0 0 1]} {:key :nested})
        (slug/canvas-command! c [:restore])
        (let [layer (slug/fill! c p {:color [1 0 0 1]} {:key :restored})]
          (is (close? 2.0 (first (:transform layer))))
          (is (close? 3.0 (second (:transform layer)))))
        (slug/canvas-command! c [:add-path p])
        (slug/define-shape! c nil :with-added-path {})))
    (is (some? (slug/shape a :canvas-verbs)))
    (is (some? (slug/shape a :with-added-path)))))

(deftest explicit-paths-are-reusable-and-stroke-options-forward
  (slug/with-atlas [a {:texture-width 128}]
    (slug/with-canvas [c a {}]
      (slug/with-path [p]
        (doseq [command [[:move-to 0 0] [:line-to 0.2 0] [:line-to 0.2 0.2]]]
          (slug/path-command! p command))
        (let [length (slug/arc-length p)]
          (doseq [join [:miter :bevel :round]
                  cap [:butt :round :square]]
            (slug/stroke! c p 0.05 {:color [0 0 0 1]}
                          {:key (keyword (str (name join) "-" (name cap)))
                           :join join :cap cap :miter-limit 4}))
          (is (close? length (slug/arc-length p))))
        (slug/fill! c p {:color [1 1 1 1]} {:key :reuse-a})
        (slug/fill! c p {:color [1 1 1 1]} {:key :reuse-b})
        (slug/finalize! c :strokes))
      (let [layers (:layers (slug/composite a :strokes))]
        (is (= 11 (count layers)))
        (is (= ["miter-butt" "round-square" "reuse-a" "reuse-b"]
               [(-> layers first :key)
                (-> layers (nth 8) :key)
                (-> layers (nth 9) :key)
                (-> layers (nth 10) :key)]))
        (doseq [layer (take 9 layers)]
          (is (pos? (:width (slug/shape a (:key layer))))))))))

(deftest flat-gradient-and-pattern-commits-preserve-paint-ids-and-order
  (slug/with-atlas [a {:texture-width 128}]
    (slug/with-canvas [c a {}]
      (slug/with-path [tile]
        (slug/path-command! tile [:rect 0 0 1 1])
        (slug/define-shape! c tile :tile {}))
      (let [gid (slug/add-gradient! a
                  {:type :linear :from [0 0] :to [1 0]
                   :stops [[0 [1 0 0 1]] [1 [0 0 1 1]]]})
            pid (slug/add-pattern! a
                  {:tile :tile :cell [0 0 1 1]
                   :repeat-x :reflect :repeat-y :repeat})]
        (is (pos? gid))
        (is (pos? pid))
        (slug/with-path [p]
          (slug/path-command! p [:rect 0 0 1 1])
          (slug/fill! c p {:color [1 0 0 1]} {:key :flat})
          (slug/stroke! c p 0.1 {:color [0 1 0 1]} {:key :outline})
          (slug/fill! c p {:gradient-id gid} {:key :gradient})
          (slug/stroke! c p 0.1 {:gradient-id gid} {:key :gradient-outline})
          (slug/fill! c p {:pattern-id pid} {:key :pattern})
          (slug/stroke! c p 0.1 {:pattern-id pid} {:key :pattern-outline}))
        (slug/finalize! c :painted)
        (let [layers (:layers (slug/composite a :painted))]
          (is (= ["flat" "outline" "gradient" "gradient-outline" "pattern" "pattern-outline"]
                 (mapv :key layers)))
          (is (= [0 0 gid gid 0 0] (mapv :gradient-id layers)))
          (is (= [0 0 0 0 pid pid] (mapv :pattern-id layers))))))))

(deftest masks-text-and-composite-order-are-native-metadata
  (slug/with-atlas [a {:texture-width 128}]
    (slug/add-shape! a [:codepoint 65] rectangle-curves {})
    (slug/with-canvas [c a {}]
      (slug/with-path [p]
        (slug/path-command! p [:rect 0 0 1 1])
        (slug/fill! c p {:color [1 0 0 1]} {:key :first}))
      (slug/text! c "A" {:size 1 :at [2 3] :color [1 1 1 1]
                         :metrics {:cap-height-ratio 0.7 :x-height-ratio 0.5}
                         :anchor-y :baseline :align-x :left})
      (slug/with-path [p]
        (slug/path-command! p [:rect 0 0 1 1])
        (slug/fill! c p {:color [0 0 1 1]} {:key :last}))
      (slug/set-mask! c {:type :circle :params [0.5 0.5 0.25] :invert true})
      (slug/finalize! c :masked)
      (let [composite (slug/composite a :masked)]
        (is (= ["first" [:codepoint 65] "last"] (mapv :key (:layers composite))))
        (is (= {:type :circle :params [0.5 0.5 0.25 0.0 0.0 0.0] :invert true}
               (:mask composite)))))))

(deftest lifecycle-and-native-errors-are-safe
  (slug/with-path [p]
    (slug/destroy! p)
    (slug/destroy! p)
    (is (= :sample (:op (ex-data (capture-ex-info #(slug/sample p 0.5)))))))
  (let [atlas (slug/make-atlas)
        canvas (slug/make-canvas atlas {})]
    (slug/destroy! atlas)
    (let [error (capture-ex-info #(slug/canvas-command! canvas [:begin-path]))]
      (is (some? error))
      (is (= :canvas-command! (:op (ex-data error)))))
    (slug/destroy! canvas)
    (slug/destroy! canvas))
  (slug/with-atlas [a {:texture-width 128}]
    (slug/with-canvas [c a {}]
      (let [error (capture-ex-info #(slug/fill! c nil {:color [1 0 0 1]} {}))]
        (is (some? error))
        (is (= :fill! (:op (ex-data error))))
        (is (seq (:native-error (ex-data error)))))
      (slug/with-path [p]
        (slug/path-command! p [:rect 0 0 1 1])
        (doseq [paint [{}
                       {:color nil}
                       {:gradient-id nil}
                       {:gradient-id false}
                       {:pattern-id nil}
                       {:pattern-id false}
                       {:color [1 0 0 1] :gradient-id 1}
                       {:gradient-id 1 :pattern-id 1}
                       {:color [1 0 0]}]]
          (let [error (capture-ex-info #(slug/fill! c p paint {}))]
            (is (some? error))
            (is (= :fill! (:op (ex-data error))))))))))

(deftest add-path-surfaces-native-errors
  (slug/with-path [p]
    (slug/with-path [source]
      (with-redefs [slipstream.ffi/path-add (constantly -1)
                    slipstream.ffi/last-error (constantly "injected add-path failure")]
        (let [error (capture-ex-info #(slug/path-command! p [:add-path source]))]
          (is (some? error))
          (is (= :path-command! (:op (ex-data error))))
          (is (= "injected add-path failure" (:native-error (ex-data error)))))))))

(deftest fill-and-stroke-return-stable-named-keys
  (slug/with-atlas [a {:texture-width 128}]
    (slug/with-canvas [c a {}]
      (slug/with-path [p]
        (slug/path-command! p [:rect 0 0 1 1])
        (doseq [i (range 8)]
          (let [fill-key (str (apply str (repeat 512 "fill-key-")) i)
                stroke-key (str (apply str (repeat 512 "stroke-key-")) i)]
            (is (= fill-key (:key (slug/fill! c p {:color [1 0 0 1]} {:key fill-key}))))
            (is (= stroke-key (:key (slug/stroke! c p 0.1 {:color [0 1 0 1]} {:key stroke-key}))))))))))
