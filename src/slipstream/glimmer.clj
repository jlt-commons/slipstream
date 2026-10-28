(ns slipstream.glimmer
  (:require [glimmer.backend :as b]
            [slipstream.core :as core]
            [slipstream.glimmer-compiler :as compiler]
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
  #{:at :transform :scale :color :visible :visibility :opacity
    :effect-id :effect-param :gradient-id :pattern-id :bleed
    :draw-mode :blend-mode})

(defn- layer-prop? [k]
  (or (contains? layer-props k)
      (.startsWith (name k) "on-")))

(defn- cache-key [tag props]
  [tag (into {} (remove (comp layer-prop? key) props))])

(defn- native-context? [context]
  (instance? slipstream.glimmer_renderer.GLContext context))

(defn- native-acquisitions [context]
  {:shape (fn [props]
            (renderer/acquire-resource! context (compiler/compile-shape props)))
   :glyph (fn [font codepoint props]
            (renderer/acquire-resource! context
              (compiler/compile-glyph font codepoint props)))})

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
  (let [widget {:tag tag :props props :children [] :context *context*
                :resource nil :disposed? false}]
    (atom
      (case tag
        :text (assoc widget :resources (acquire-text! *context* props))
        (:shape :path) (assoc widget :resource
                         ((:shape (native-acquisitions *context*)) props))
        widget))))

(defn- create! [tag props]
  (if (native-context? *context*)
    (create-native! tag props)
    (atom {:tag tag
           :props props
           :children []
           :context *context*
           :resource (resources/acquire! *factory* *context* (cache-key tag props) props)
           :disposed? false})))

(defn- apply-props! [tag widget props]
  (when-not (:disposed? @widget)
    (let [{old-props :props resource :resource context :context} @widget]
      (if (native-context? context)
        (compiler/update-node! widget old-props props (native-acquisitions context))
        (let [old-key (cache-key tag old-props)
              new-key (cache-key tag props)]
          (cond
            (= old-props props)
            nil

            (= old-key new-key)
            (do
              (resources/update-layers! resource props)
              (swap! widget assoc :props props))

            :else
            (let [replacement (resources/acquire! (:factory resource) context new-key props)]
              (swap! widget assoc :props props :resource replacement)
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
  (let [paint (or (flat-color (:color props))
                  (flat-color (:fill props))
                  (flat-color (:paint props))
                  (flat-color (get-in props [:paints (:fill props)]))
                  (flat-color (get-in props [:paints (:paint props)]))
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

(defn- resource-command [resource transform scale color]
  {:handle (renderer/native-handle resource)
   :transform transform
   :scale scale
   :color color})

(defn- widget-commands [widget parent-transform parent-scale parent-color]
  (let [{:keys [tag props resource resources children]} @widget]
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
                         (:shape :path) 1
                         (get props :scale 1))
            scale (* parent-scale node-scale)]
        (case tag
          :text
          (first
            (reduce
              (fn [[commands advance] glyph]
                (let [shape (core/shape (:atlas glyph) (get-in glyph [:root :key]))
                      command (resource-command glyph
                                [(+ (first transform) (* parent-scale advance))
                                 (second transform) (nth transform 2)]
                                scale color)]
                  [(conj commands command)
                   (+ advance (* (:advance shape) node-scale))]))
              [[] 0] resources))

          (:shape :path)
          [(resource-command resource transform scale color)]

          (vec (mapcat #(widget-commands % transform scale color) children)))))))

(defn- draw-tree [scene]
  (vec (mapcat #(widget-commands % [0 0 0] 1 [1 1 1 1]) (:children @scene))))

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
               :auto-quit-ms (:auto-quit-ms opts)})
            (finally
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
