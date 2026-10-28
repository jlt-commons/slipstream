(ns slipstream.example.demoscene-profiles
  (:require [slipstream.example.demoscene-shader :as visuals]))

(def profile-names
  [:showcase :geometry :text :compositor :shader :all])

(def max-intensity 16)
(def mesh-vertex-limit 65536)
(def ^:private geometry-segments-per-intensity 256)
(def ^:private estimated-vertices-per-segment 16)

(defn profile-config [profile {:keys [intensity sdf] :or {intensity 1}}]
  (when-not (some #{profile} profile-names)
    (throw (ex-info (str "Unknown demoscene profile: " (pr-str profile)
                         "; expected one of " (pr-str profile-names))
                    {:reason :unknown-profile :profile profile
                     :valid-profiles profile-names})))
  (when-not (and (integer? intensity) (<= 1 intensity max-intensity))
    (throw (ex-info (str "Demoscene intensity must be an integer between 1 and "
                         max-intensity)
                    {:reason :invalid-intensity :intensity intensity
                     :minimum 1 :maximum max-intensity})))
  (when-not (or (nil? sdf) (boolean? sdf))
    (throw (ex-info "Demoscene :sdf must be true, false, or nil"
                    {:reason :invalid-sdf :sdf sdf})))
  {:profile profile :intensity intensity :sdf sdf})

(defn- validate-mesh-budget! [{:keys [profile intensity]}]
  (let [estimated (* intensity geometry-segments-per-intensity
                     estimated-vertices-per-segment)]
    (when (and (contains? #{:geometry :all} profile)
               (> estimated mesh-vertex-limit))
      (throw (ex-info (str "Demoscene geometry path exceeds the 65536-vertex mesh limit: "
                           estimated " estimated vertices")
                      {:reason :mesh-limit :profile profile :intensity intensity
                       :estimated-vertices estimated :mesh-limit mesh-vertex-limit})))))

(defn- state-bounds [{:keys [bounds aspect]}]
  (vec (or bounds [0 aspect 0 1])))

(defn- geometry-path [i segments stage]
  (let [phase (* i 0.371)
        direction (if (odd? stage) -1.0 1.0)]
    (into [[:move-to 0.0 0.0]]
          (concat
            (map (fn [point]
                   (let [angle (+ phase (* point 0.173))
                         radius (+ 0.35 (* 0.45 (/ (double (mod point 17)) 16)))]
                     [:line-to (* direction radius (Math/cos angle))
                      (* radius (Math/sin angle))]))
                 (range segments))
            [[:close]]))))

(defn- geometry-fill [i]
  (case (mod i 3)
    0 [(+ 0.25 (* 0.1 (mod i 5))) 0.62 0.9 0.82]
    1 {:type :linear :from [-0.5 -0.5] :to [0.5 0.5]
       :stops [[0.0 [0.1 0.35 0.9 0.9]]
               [1.0 [(+ 0.5 (* 0.08 (mod i 4))) 0.2 0.75 0.9]]]}
    2 {:type :radial :center [0.0 0.0] :inner-radius 0.0 :outer-radius 0.8
       :stops [[0.0 [0.9 0.85 0.25 0.9]] [1.0 [0.45 0.08 0.5 0.85]]]}))

(defn- geometry-node [kind i definition {:keys [time geometry-stage] :as state} bounds count]
  (let [[left right bottom top] bounds
        width (- right left)
        height (- top bottom)
        column (mod i 8)
        row (quot i 8)
        x (+ left (* width (/ (+ 0.5 column) (max 8 (min 8 count)))))
        y (+ bottom (* height (- 0.72 (* 0.12 row)))
             (* 0.025 height (Math/sin (+ time i))))
        id (keyword (str "geometry-" (name kind)) (str i))
        color [1.0 (+ 0.7 (* 0.2 (Math/sin (+ time i)))) 1.0 1.0]]
    [:shape (cond-> (merge definition
                           {:id id :key id :at [x y 0.0]
                            :scale (* height 0.055)
                            :color color
                            :texture-width 128})
              (:sdf state)
              (assoc :sdf true :sdf-type :msdf :sdf-range 0.08
                     :effect {:id (+ 2000 i) :param 1.0 :sdf true :bleed 0.08
                              :fragment-hook visuals/sdf-fragment
                              :uniforms {:glow {:type :float :value 0.6}}}))]))

(defn- geometry-nodes [state bounds]
  (let [intensity (:intensity state)
        count (* 4 intensity)
        segments (* intensity geometry-segments-per-intensity)
        stage (:geometry-stage state 0)
        definitions (mapv (fn [i]
                            {:path (geometry-path i segments stage)
                             :fill (geometry-fill i)})
                          (range count))]
    (concat
      (map-indexed (fn [i definition]
                     (geometry-node :unique i definition state bounds count))
                   definitions)
      (map-indexed (fn [i definition]
                     (geometry-node :shared i definition state bounds count))
                   definitions))))

(def ^:private text-corpus
  "SLIPSTREAM ÀÉÎÕÜ àéîõü ÇÑ øå ÆŒ ß λΩπδζ ¿¡ €£¥ ©®™ 0123456789 !?@#$%&*()[]{}")

(defn- text-nodes [state bounds]
  (let [[left _right bottom top] bounds
        width (- (nth bounds 1) left)
        height (- top bottom)
        time (:time state 0.0)
        revision (long (/ time 2.0))]
    (map (fn [i]
           (let [id (keyword "stress-text" (str i))]
             [:text {:id id :key id :font (:font state)
                     :text (str text-corpus " / LINE " i " / " text-corpus
                                (if (odd? revision) " / REVISED" ""))
                     :at [(+ left (* 0.025 height)
                             (* 0.01 width (Math/sin (+ time i))))
                          (+ bottom (* height (- 0.79 (* 0.065 i)))) 0.0]
                     :size (* 0.018 height)
                     :color [(+ 0.65 (* 0.2 (Math/sin (+ time i)))) 0.85 1.0 1.0]}]))
         (range (* 4 (:intensity state))))))

(def ^:private compositor-blends [:multiply :screen :overlay :src-over])

(defn- compositor-mask [state bounds i nested?]
  (if (and (:sdf state) (not nested?))
    {:type :sdf-tile :params [0 0 1]
     :path [[:rect 0.0 0.0 (- (nth bounds 1) (nth bounds 0))
             (- (nth bounds 3) (nth bounds 2))]]
     :sdf true :sdf-range 0.1 :texture-width 128}
    (if nested?
      {:type :circle :params [0.5 0.5 (+ 0.3 (* 0.02 i))]}
      {:type :rect :params [0.02 0.02 0.96 0.96] :invert (odd? i)})))

(defn- compositor-nodes [state bounds]
  (let [[left right bottom top] bounds
        width (- right left)
        height (- top bottom)
        rect [[:rect 0.0 0.0 width height]]]
    (mapcat
      (fn [i]
        (let [outer-id (keyword "compositor-outer" (str i))
              nested-id (keyword "compositor-nested" (str i))
              shape-id (keyword "compositor-shape" (str i))
              pattern-id (keyword "compositor-pattern" (str i))]
          [[:group {:id outer-id :key outer-id
                    :mask (compositor-mask state bounds i false)
                    :blend-mode (nth compositor-blends (mod i (count compositor-blends)))}
            [:group {:id nested-id :key nested-id
                     :mask (compositor-mask state bounds i true)
                     :blend-mode (if (even? i) :src-over :screen)}
             [:shape {:id shape-id :key shape-id :path rect :at [left bottom 0.0]
                      :fill {:type :linear :from [left bottom] :to [right top]
                             :stops [[0.0 [0.08 0.12 0.35 0.55]]
                                     [1.0 [(+ 0.25 (* 0.1 i)) 0.65 0.8 0.55]]]}}]
             [:shape {:id pattern-id :key pattern-id :path rect :at [left bottom 0.0]
                      :fill {:type :pattern :tile (keyword (str "profile-tile-" i))
                             :tile-path [[:rect 0.0 0.0 0.45 0.45]
                                         [:rect 0.55 0.55 0.45 0.45]]
                             :cell [0.0 0.0 1.0 1.0]
                             :repeat-x :repeat :repeat-y :reflect}
                      :color [0.5 0.8 1.0 0.35]}]]]]))
      (range (* 2 (:intensity state))))))

(defn- shader-nodes [state bounds]
  (let [[left right bottom top] bounds
        width (- right left)
        height (- top bottom)
        effect (visuals/shader-effect (assoc state :density 4.0))]
    (map (fn [i]
           (let [id (keyword "shader-pass" (str i))]
             [:shape {:id id :key id :path [[:rect 0.0 0.0 width height]]
                      :at [left bottom 0.0] :fill [0.015 0.02 0.05 1.0]
                      :effect effect}]))
         (range (:intensity state)))))

(defn- hud-nodes [state bounds]
  (let [[left _right bottom top] bounds
        height (- top bottom)
        font (:font state)
        profile (:profile state)
        metrics (:metrics-text state)]
    (cond-> [[:text {:id :hud-title :key :hud-title :font font
                     :text (str "SLIPSTREAM / " (.toUpperCase (name profile)) " STRESS")
                     :at [(+ left (* 0.04 height)) (+ bottom (* 0.94 height)) 0.0]
                     :size (* 0.028 height) :color [0.78 0.9 1.0 1.0]}]
             [:text {:id :hud-status :key :hud-status :font font
                     :text (format "PROFILE %s   INTENSITY %d   SDF %s   NREPL 127.0.0.1:%s"
                                   (.toUpperCase (name profile))
                                   (long (:intensity state))
                                   (if (:sdf state) "ON" "OFF")
                                   (or (:nrepl-port state) "offline"))
                     :at [(+ left (* 0.04 height)) (+ bottom (* 0.895 height)) 0.0]
                     :size (* 0.017 height) :color [0.62 0.82 0.96 1.0]}]
             [:text {:id :hud-controls :key :hud-controls :font font
                     :text "NREPL LOAD-PROFILE!   INTENSITY 1-16   R RESET CURRENT PROFILE"
                     :at [(+ left (* 0.04 height)) (+ bottom (* 0.045 height)) 0.0]
                     :size (* 0.019 height) :color [0.55 0.72 0.88 1.0]}]]
      (and (not= false (:metrics state)) (seq metrics))
      (conj [:text {:id :hud-metrics :key :hud-metrics :font font :text metrics
                    :at [(+ left (* 0.04 height)) (+ bottom (* 0.85 height)) 0.0]
                    :size (* 0.015 height) :color [0.65 0.9 0.75 1.0]}]))))

(defn- with-showcase-metrics [state scene]
  (if (and (not= false (:metrics state)) (seq (:metrics-text state)))
    (let [bounds (state-bounds state)
          [left _right bottom top] bounds
          height (- top bottom)]
      (conj scene [:text {:id :hud-metrics :key :hud-metrics :font (:font state)
                          :text (:metrics-text state)
                          :at [(+ left (* 0.04 height)) (+ bottom (* 0.85 height)) 0.0]
                          :size (* 0.015 height) :color [0.65 0.9 0.75 1.0]}]))
    scene))

(defn profile-scene [state]
  (let [_ (when (integer? (:intensity state))
            (validate-mesh-budget! {:profile (:profile state :showcase)
                                    :intensity (:intensity state)}))
        {:keys [profile] :as config} (profile-config (:profile state :showcase) state)
        state (merge state config)
        bounds (state-bounds state)]
    (if (= :showcase profile)
      (with-showcase-metrics state (visuals/scene-for-state state))
      (into [:group {}]
            (concat
              (when (contains? #{:shader :all} profile) (shader-nodes state bounds))
              (when (contains? #{:geometry :all} profile) (geometry-nodes state bounds))
              (when (contains? #{:text :all} profile) (text-nodes state bounds))
              (when (contains? #{:compositor :all} profile) (compositor-nodes state bounds))
              (hud-nodes state bounds))))))
