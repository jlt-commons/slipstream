(ns slipstream.glimmer
  (:require [glimmer.backend :as b]
            [slipstream.glimmer-compiler :as compiler]
            [slipstream.glimmer-effects :as effects]
            [slipstream.glimmer-renderer :as renderer]
            [slipstream.glimmer-resources :as resources]))

(def ^:private default-factory
  {:create! (fn [_ cache-key props]
              {:cache-key cache-key :props props})
   :release! (fn [_] nil)
   :update-layers! (fn [_ _] nil)})

(def ^:dynamic *factory* default-factory)
(def ^:dynamic *context* nil)
(defonce ^:private active-context (atom nil))

(def ^:private layer-props
  #{:at :transform :scale :color :visible :visibility :opacity :scene-path
    :effect :effect-id :effect-param :gradient-id :pattern-id :bleed
    :draw-mode :blend-mode})

(defn- layer-prop? [k]
  (or (contains? layer-props k)
      (.startsWith (name k) "on-")))

(defn- cache-key [tag props]
  [tag (into {} (remove (comp layer-prop? key) props))])

(defn- native-context? [context]
  (instance? slipstream.glimmer_renderer.GLContext context))

(defn- sdf-mask? [props]
  (= :sdf-tile (get-in props [:mask :type])))

(defn- mask-node [props]
  (when (sdf-mask? props)
    (let [mask (:mask props)
          path (or (:path mask) (:tile-path mask))]
      (when-not (sequential? path)
        (throw (ex-info "SDF tile mask requires a :path or :tile-path"
                        {:scene-path (conj (vec (or (:scene-path props) [(:tag props)]))
                                           :mask :path)})))
      (merge (select-keys props [:texture-width :curve-precision :sdf-type :sdf-range])
             mask
             {:path path :sdf true
              :scene-path (conj (vec (or (:scene-path props) [(:tag props)])) :mask)}))))

(defn- native-acquisitions [context]
  {:shape (fn [props]
            (renderer/acquire-resource! context (compiler/compile-shape props)))
   :glyph (fn [font codepoint props]
            (renderer/acquire-resource! context
              (compiler/compile-glyph font codepoint props)))
   :mask (fn [props]
           (renderer/acquire-resource! context (compiler/compile-shape (mask-node props))))})

(defn- acquire-text! [context props]
  (let [acquire (:glyph (native-acquisitions context))
        acquired (atom [])]
    (try
      (mapv (fn [codepoint]
              (let [resource (acquire (:font props) codepoint props)]
                (swap! acquired conj resource)
                resource))
            (compiler/text-codepoints props))
      (catch Throwable error
        (doseq [resource @acquired]
          (resources/release! resource))
        (throw error)))))

(defn- create-native! [tag props]
  (let [widget {:tag tag :props props :scene-path (or (:scene-path props) [tag])
                :children [] :context *context* :resource nil :mask-resource nil
                :disposed? false}]
    (atom
      (case tag
        :text (assoc widget :resources (acquire-text! *context* props))
        (:shape :path) (assoc widget :resource
                         ((:shape (native-acquisitions *context*)) props))
        (:group :composite :scene) (assoc widget :mask-resource
                                    (when (sdf-mask? props)
                                      ((:mask (native-acquisitions *context*)) props)))
        widget))))

(defn- create! [tag props]
  (compiler/validate-paint-references! (assoc props :tag tag))
  (if (native-context? *context*)
    (create-native! tag props)
    (atom {:tag tag
           :props props
           :scene-path (or (:scene-path props) [tag])
           :children []
           :context *context*
           :resource (resources/acquire! *factory* *context* (cache-key tag props) props)
           :disposed? false})))

(defn- update-native-group! [widget old-props new-props context]
  (let [old-mask (mask-node old-props)
        new-mask (mask-node new-props)]
    (if (= (compiler/geometry-key old-mask) (compiler/geometry-key new-mask))
      (swap! widget assoc :props new-props :scene-path (or (:scene-path new-props) [:group]))
      (let [old-resource (:mask-resource @widget)
            replacement (when new-mask
                          ((:mask (native-acquisitions context)) new-props))]
        (swap! widget assoc :props new-props :mask-resource replacement
               :scene-path (or (:scene-path new-props) [:group]))
        (resources/release! old-resource)))))

(defn- apply-props! [tag widget props]
  (when-not (:disposed? @widget)
    (compiler/validate-paint-references! (assoc props :tag tag))
    (let [{old-props :props resource :resource context :context} @widget]
      (if (native-context? context)
        (if (contains? #{:group :composite :scene} tag)
          (update-native-group! widget old-props props context)
          (compiler/update-node! widget old-props props (native-acquisitions context)))
        (let [old-key (cache-key tag old-props)
              new-key (cache-key tag props)]
          (cond
            (= old-props props)
            nil

            (= old-key new-key)
            (do
              (resources/update-layers! resource props)
              (swap! widget assoc :props props
                     :scene-path (or (:scene-path props) [tag])))

            :else
            (let [replacement (resources/acquire! (:factory resource) context new-key props)]
              (swap! widget assoc :props props :resource replacement
                     :scene-path (or (:scene-path props) [tag]))
              (resources/release! resource)))))))
  nil)

(defn- index-of [children child]
  (first (keep-indexed (fn [i candidate]
                         (when (identical? candidate child) i))
                       children)))

(defn- append-child! [_parent-tag parent child]
  (swap! parent update :children conj child)
  nil)

(defn- remove-child! [_parent-tag parent child]
  (swap! parent update :children
         (fn [children]
           (vec (remove #(identical? % child) children))))
  (resources/dispose-tree! child)
  nil)

(defn- replace-child! [_parent-tag parent old-child new-child]
  (swap! parent update :children
         (fn [children]
           (if-let [i (index-of children old-child)]
             (assoc children i new-child)
             (conj children new-child))))
  (resources/dispose-tree! old-child)
  nil)

(defn- reorder-child! [_parent-tag parent child sibling]
  (swap! parent update :children
         (fn [children]
           (let [without (vec (remove #(identical? % child) children))
                 position (if sibling
                            (if-let [i (index-of without sibling)] (inc i) (count without))
                            0)]
             (vec (concat (subvec without 0 position)
                          [child]
                          (subvec without position))))))
  nil)

(defn- flat-color [value]
  (when (and (vector? value) (= 4 (count value)) (every? number? value)) value))

(defn- node-color [props resource]
  (let [advanced? (or (pos? (get-in resource [:root :gradient-id] 0))
                      (pos? (get-in resource [:root :pattern-id] 0)))
        paint (or (flat-color (:color props))
                  (flat-color (:fill props))
                  (flat-color (:paint props))
                  (flat-color (get-in props [:paints (:fill props)]))
                  (flat-color (get-in props [:paints (:paint props)]))
                  (when advanced? [1 1 1 (get-in resource [:root :color 3] 1)])
                  (get-in resource [:root :color])
                  [1 1 1 1])]
    (assoc (vec paint) 3 (* (nth paint 3) (get props :opacity 1)))))

(defn- visible? [props]
  (and (not= false (:visible props))
       (not= :hidden (:visibility props))
       (not= :hidden (:draw-mode props))
       (not= 1 (:draw-mode props))))

(defn- position [props]
  (let [value (get props :transform (get props :at [0 0 0]))]
    (into (vec value) (repeat (- 3 (count value)) 0))))

(defn- resource-command [resource transform scale color props]
  (let [scene-path (vec (or (:scene-path props) [:shape]))
        effect (when-let [data (:effect props)]
                 (effects/effect-program!
                   (:context resource)
                   (assoc data :scene-path (conj scene-path :effect))))]
    {:handle (renderer/native-handle resource)
     :paint (renderer/native-paint-handle resource)
     :transform transform
     :scale scale
     :color color
     :scene-path scene-path
     :effect (get effect :handle 0)
     :effect-id (get props :effect-id (get effect :id 0))
     :effect-param (get props :effect-param (get effect :param 0))
     :bleed (get props :bleed (get effect :bleed 0))
     :draw-mode (get props :draw-mode :visible)
     :blend-mode (get props :blend-mode :src-over)}))

(defn- widget-commands [widget parent-transform parent-scale parent-color]
  (let [{:keys [tag props resource resources mask-resource children]} @widget]
    (if-not (visible? props)
      []
      (let [[x y z] (position props)
            transform [(+ (first parent-transform) (* parent-scale x))
                       (+ (second parent-transform) (* parent-scale y))
                       (+ (nth parent-transform 2) z)]
            color (node-color props resource)
            color (mapv * color parent-color)
            node-scale (case tag
                         :text (* (get props :size 1) (get props :scale 1))
                         (:shape :path) (get props :scale 1)
                         (get props :scale 1))
            scale (* parent-scale node-scale)]
        (case tag
          :text
          (let [advances (mapv #(* node-scale (get-in % [:shape :advance])) resources)
                total-advance (reduce + 0 advances)
                align-offset (case (get props :align :left)
                               :center (- (/ total-advance 2))
                               :right (- total-advance)
                               0)
                metrics (:metrics (first resources))
                anchor-ratio (case (get props :anchor :baseline)
                               :cap-center (* 0.5 (:cap-height-ratio metrics))
                               :cap-top (:cap-height-ratio metrics)
                               :x-center (* 0.5 (:x-height-ratio metrics))
                               0)
                origin [(+ (first transform) align-offset)
                        (- (second transform) (* node-scale (or anchor-ratio 0)))
                        (nth transform 2)]]
            (first
              (reduce
                (fn [[commands advance] [glyph glyph-advance]]
                  (let [command (resource-command glyph
                                  [(+ (first origin) (* parent-scale advance))
                                   (second origin) (nth origin 2)]
                                  scale color props)]
                    [(conj commands command) (+ advance glyph-advance)]))
                [[] 0] (mapv vector resources advances))))

          (:shape :path)
          [(resource-command resource transform scale color props)]

          (let [mask (:mask props)
                blend-mode (get props :blend-mode :src-over)
                offscreen? (or mask (not= :src-over blend-mode))
                scene-path (vec (or (:scene-path props) [tag]))
                commands (vec (mapcat #(widget-commands % transform scale
                                                        (if offscreen? parent-color color))
                                      children))]
            (if offscreen?
              (into [{:begin-group mask
                      :mask-handle (if mask-resource (renderer/native-handle mask-resource) 0)
                      :transform transform :scale scale :color color
                      :scene-path scene-path}]
                    (conj commands {:end-group blend-mode :scene-path scene-path}))
              commands)))))))

(defn- draw-tree [scene]
  (vec (mapcat #(widget-commands % [0 0 0] 1 [1 1 1 1]) (:children @scene))))

(defn current-context
  "Returns the native context of the running Slipstream scene, or nil headlessly."
  []
  @active-context)

(defn- schedule [work]
  (if-let [context @active-context]
    (renderer/schedule! context work)
    (work))
  nil)

(defn- run-headless-lifecycle [opts {:keys [mount! unmount!]}]
  (let [scene (create! :scene opts)]
    (try
      (mount! scene :scene)
      (finally
        (try
          (unmount!)
          (finally
            (resources/dispose-tree! scene)))))))

(defn- run-native-lifecycle [opts {:keys [mount! unmount!]}]
  (let [context (renderer/create-context opts)
        previous-context @active-context
        previous-running @b/loop-running?]
    (reset! active-context context)
    (try
      (binding [*context* context]
        (let [scene (create! :scene opts)]
          (try
            (mount! scene :scene)
            (reset! b/loop-running? true)
            (renderer/run-loop! context
              {:draw-tree #(draw-tree scene)
               :max-frames (:max-frames opts)
               :auto-quit-ms (:auto-quit-ms opts)
               :on-start (:on-start opts)
               :after-render (:after-render opts)})
            (finally
              (renderer/stop-scheduling! context)
              (reset! b/loop-running? previous-running)
              (try
                (unmount!)
                (finally
                  (resources/dispose-tree! scene)))))))
      (finally
        (reset! active-context previous-context)
        (renderer/destroy! context)))))

(defn- run-lifecycle [opts lifecycle]
  (if *context*
    (run-headless-lifecycle opts lifecycle)
    (run-native-lifecycle opts lifecycle)))

(b/register!
 {:name :slipstream
  :create! create!
  :apply-props! apply-props!
  :append-child! append-child!
  :remove-child! remove-child!
  :replace-child! replace-child!
  :reorder-child! reorder-child!
  :dispose! resources/dispose-tree!
  :schedule schedule
  :run-lifecycle run-lifecycle})
