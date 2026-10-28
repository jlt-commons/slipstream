(ns slipstream.example.demoscene-shader)

(def initial-state
  {:time 0.0
   :transition 0.0
   :transition-target 0.0
   :mouse [0.5 0.5]
   :density 1.0
   :speed 1.0
   :burst 0.0
   :left false
   :right false
   :middle false
   :space false
   :r false
   :aspect 1.5
   :vectors {}})

(def palette
  {:a [0.24 0.28 0.4]
   :b [0.34 0.3 0.34]
   :c [1.0 1.0 1.0]
   :d [0.0 0.16 0.34]})

(def shader-fragment
  "vec2 demoHash(vec2 p) {
     vec3 q = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
     q += dot(q, q.yzx + 33.33);
     return fract((q.xx + q.yz) * q.zy);
   }
   float demoVoronoi(vec2 p) {
     vec2 cell = floor(p);
     vec2 local = fract(p);
     float nearest = 8.0;
     float second = 8.0;
     for (int y = -1; y <= 1; y++) {
       for (int x = -1; x <= 1; x++) {
         vec2 neighbor = vec2(float(x), float(y));
         vec2 point = demoHash(cell + neighbor);
         vec2 delta = neighbor + point - local;
         float distance = dot(delta, delta);
         if (distance < nearest) {
           second = nearest;
           nearest = distance;
         } else if (distance < second) {
           second = distance;
         }
       }
     }
     float edge = sqrt(second) - sqrt(nearest);
     return clamp(0.72 * sqrt(nearest) + 0.55 * (1.0 - smoothstep(0.015, 0.12, edge)), 0.0, 1.0);
   }
   float demoWaves(vec2 p, float t) {
     float a = sin(p.x * 2.7 + t * 0.9 + sin(p.y * 2.1 - t * 0.35));
     float b = sin(p.y * 3.4 - t * 0.7 + sin(p.x * 1.8 + t * 0.45));
     float c = sin((p.x + p.y) * 2.0 + t * 0.3);
     float interference = sin(length(p) * 3.2 - t * 0.8 + a * 0.8);
     return 0.5 + 0.125 * (a + b + c + interference);
   }
   vec3 demoPalette(float t) {
     return u_paletteA + u_paletteB * cos(6.28318530718 * (u_paletteC * t + u_paletteD));
   }
   vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) {
     vec2 uv = vec2(emCoord.x / u_aspect, emCoord.y);
     vec2 p = (uv - 0.5) * vec2(u_aspect, 1.0);
     vec2 mouse = (u_mouse - 0.5) * vec2(u_aspect, 1.0);
     vec2 mouseDelta = p - mouse;
     float mouseField = exp(-5.0 * dot(mouseDelta, mouseDelta));
     float t = u_time * u_speed;
     p += mouseField * 0.12 * vec2(sin(mouseDelta.y * 9.0 + t),
                                   cos(mouseDelta.x * 9.0 - t));
     vec2 q = p * (2.5 + u_density * 2.4);
     float waves = demoWaves(q, t);
     float cells = demoVoronoi(q + vec2(t * 0.12, -t * 0.08));
     float morph = smoothstep(0.0, 1.0, u_transition);
     float field = mix(waves, cells, morph);
     float threads = pow(0.5 + 0.5 * sin(field * 15.0 + t * 0.55), 5.0);
     float luminance = 0.16 + 0.72 * field + 0.36 * threads + mouseField * 0.12;
     vec3 rgb = demoPalette(luminance + t * 0.025 + morph * 0.08);
     rgb += vec3(0.08, 0.16, 0.24) * threads;
     float vignette = 1.0 - smoothstep(0.35, 1.15, length(p * vec2(0.72, 1.0)));
     rgb *= 0.42 + 0.58 * vignette;
     return vec4(clamp(rgb, 0.0, 1.0), 1.0);
   }")

(def sdf-fragment
  "vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) {
     float coverage = slipstream_sdf_coverage(sd, fwidth(emCoord));
     float rim = 1.0 - smoothstep(0.0, 0.16, abs(sd));
     vec3 luminous = color.rgb * (0.82 + u_glow * 0.35) + rim * u_glow * vec3(0.12, 0.2, 0.28);
     return vec4(clamp(luminous, 0.0, 1.0), color.a * coverage);
   }")

(defn- clamp [value low high]
  (max low (min high value)))

(defn- mix [a b amount]
  (+ (* a (- 1.0 amount)) (* b amount)))

(defn- approach [value target amount]
  (if (< value target)
    (min target (+ value amount))
    (max target (- value amount))))

(defn update-state [state dt input]
  (if (:r input)
    (merge initial-state (select-keys state [:aspect :font]))
    (let [dt (max 0.0 (double dt))
          space-toggle (and (:space input) (not (:space state)))
          target (if space-toggle (- 1.0 (:transition-target state 0.0))
                     (:transition-target state 0.0))
          burst (if (and (:left input) (not (:left state)))
                  1.0
                  (max 0.0 (- (:burst state 0.0) (* dt 1.4))))]
      (assoc state
             :time (+ (:time state 0.0) dt)
             :transition (approach (:transition state 0.0) target (* dt 1.8))
             :transition-target target
             :mouse (vec (or (:mouse input) (:mouse state)))
             :density (clamp (+ (:density state 1.0) (* 0.1 (:scroll-y input 0.0)))
                             0.25 4.0)
             :burst burst
             :left (boolean (:left input))
             :right (boolean (:right input))
             :middle (boolean (:middle input))
             :space (boolean (:space input))
             :r false))))

(defn shader-effect [{:keys [time transition mouse density speed aspect]}]
  {:id 1
   :param 1.0
   :fragment-hook shader-fragment
   :uniforms {:time {:type :float :value time}
              :transition {:type :float :value transition}
              :mouse {:type :vec2 :value mouse}
              :density {:type :float :value density}
              :speed {:type :float :value speed}
              :aspect {:type :float :value aspect}
              :paletteA {:type :vec3 :value (:a palette)}
              :paletteB {:type :vec3 :value (:b palette)}
              :paletteC {:type :vec3 :value (:c palette)}
              :paletteD {:type :vec3 :value (:d palette)}}})

(defn- vector-path [kind]
  (case kind
    0 [[:circle 0.0 0.0 0.5]]
    1 [[:rect -0.48 -0.48 0.96 0.96]]
    2 [[:move-to 0.0 0.58] [:line-to -0.52 -0.42] [:line-to 0.52 -0.42] [:close]]
    3 [[:move-to 0.0 0.6] [:line-to 0.16 0.16] [:line-to 0.58 0.0]
       [:line-to 0.16 -0.16] [:line-to 0.0 -0.6] [:line-to -0.16 -0.16]
       [:line-to -0.58 0.0] [:line-to -0.16 0.16] [:close]]
    4 [[:rect -0.55 -0.16 1.1 0.32] [:rect -0.16 -0.55 0.32 1.1]]
    5 [[:move-to -0.5 -0.5] [:line-to 0.0 -0.16] [:line-to 0.5 -0.5]
       [:line-to 0.24 0.0] [:line-to 0.5 0.5] [:line-to 0.0 0.24]
       [:line-to -0.5 0.5] [:line-to -0.24 0.0] [:close]]))

(defn- vector-fill [i]
  (case (mod i 4)
    0 {:type :linear :from [-0.5 -0.5] :to [0.5 0.5]
       :stops [[0.0 [0.08 0.5 0.95 1.0]] [0.55 [0.45 0.16 0.9 1.0]]
               [1.0 [0.2 0.95 0.72 1.0]]]}
    1 [0.18 0.72 0.95 1.0]
    2 {:type :pattern :tile (keyword (str "constellation-tile-" i))
       :tile-path [[:rect 0.0 0.0 0.48 0.48] [:rect 0.52 0.52 0.48 0.48]]
       :cell [0.0 0.0 1.0 1.0] :repeat-x :repeat :repeat-y :reflect}
    3 {:type :radial :center [0.0 0.0] :inner-radius 0.0 :outer-radius 0.72
       :stops [[0.0 [0.75 0.95 1.0 1.0]] [0.45 [0.25 0.3 0.9 1.0]]
               [1.0 [0.08 0.04 0.22 1.0]]]}))

(def vector-definitions
  (mapv (fn [i]
          (cond-> {:path (vector-path (mod i 6))
                   :fill (vector-fill i)
                   :texture-width 128}
            (= 1 (mod i 6)) (assoc :stroke 0.035)
            (contains? #{4 8} i) (assoc :sdf true :sdf-type :msdf :sdf-range 0.18)))
        (range 12)))

(defn- vector-color [i time transition]
  [(+ 0.42 (* 0.36 (Math/sin (+ (* 0.37 time) (* i 1.7)))))
   (+ 0.5 (* 0.34 (Math/sin (+ (* 0.29 time) (* i 2.1) 1.4))))
   (+ 0.62 (* 0.3 (Math/cos (+ (* 0.31 time) (* i 1.3) transition))))
   1.0])

(defn- smoothstep [value]
  (let [x (clamp value 0.0 1.0)]
    (* x x (- 3.0 (* 2.0 x)))))

(defn- vector-placement [i aspect time transition mouse burst]
  (let [phase (* i 2.399963229728653)
        angle (+ phase (* time (+ 0.22 (* 0.013 i))))
        orbit-x (* aspect 0.31 (Math/cos angle))
        orbit-y (* 0.34 (Math/sin angle))
        column (mod i 4)
        row (quot i 4)
        grid-x (* aspect (- (* 0.19 column) 0.285))
        grid-y (- 0.25 (* 0.25 row))
        morph (smoothstep transition)
        cursor-x (* aspect (first mouse))
        cursor-y (second mouse)
        base-x (+ (* 0.5 aspect) (mix orbit-x grid-x morph))
        base-y (+ 0.5 (mix orbit-y grid-y morph))
        attraction (+ 0.07 (* 0.1 transition))
        burst-x (* burst 0.12 aspect (Math/cos phase))
        burst-y (* burst 0.12 (Math/sin phase))]
    [(+ (mix base-x cursor-x attraction) burst-x)
     (+ (mix base-y cursor-y attraction) burst-y)
     (* (+ 0.085 (* 0.012 (Math/sin (+ (* 1.7 time) phase))))
        (+ 1.0 (* 0.32 burst) (* 0.12 transition)))]))

(defn- vector-node [i definition {:keys [aspect time transition mouse burst speed] :as state}]
  (let [[x y scale] (vector-placement i aspect (* time speed) transition mouse burst)
        id (keyword "vector" (str i))
        base {:id id :key id :at [x y 0.0] :scale scale
              :color (vector-color i (* time speed) transition)}]
    (cond-> (merge definition base (get-in state [:vectors i]))
      (:sdf definition)
      (assoc :effect {:id (+ 100 i) :param 1.0 :sdf true :bleed 0.08
                      :fragment-hook sdf-fragment
                      :uniforms {:glow {:type :float
                                        :value (+ 0.45 (* 0.4 (Math/sin (+ time (* i 0.4)))))}}}))))

(defn scene-for-state [state]
  (let [{:keys [aspect font]} state]
    (into [:group {}
           [:shape {:id :hybrid-background :key :hybrid-background
                    :path [[:rect 0.0 0.0 aspect 1.0]]
                    :fill [0.015 0.02 0.05 1.0]
                    :effect (shader-effect state)}]]
          (concat
            (map-indexed (fn [i definition] [:shape (vector-node i definition state)])
                         vector-definitions)
            [[:text {:id :hud-title :key :hud-title :font font
                     :text "SLIPSTREAM / HYBRID FIELDS"
                     :at [0.04 0.94 0.0] :size 0.028 :color [0.78 0.9 1.0 1.0]}]
             [:text {:id :hud-controls :key :hud-controls :font font
                     :text "MOVE ATTRACT   CLICK BURST   SPACE MORPH   WHEEL DENSITY   R RESET"
                     :at [0.04 0.045 0.0] :size 0.019 :color [0.55 0.72 0.88 1.0]}]]))))
