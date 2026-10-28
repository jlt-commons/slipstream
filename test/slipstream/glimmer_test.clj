(ns slipstream.glimmer-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.backend :as b]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-resources :as resources]))

(defn- fake-factory [calls]
  (let [next-id (atom 0)]
    {:create! (fn [context cache-key props]
                (when (:fail-acquire? props)
                  (throw (ex-info "injected acquire failure" {:cache-key cache-key})))
                (let [handle {:id (swap! next-id inc) :cache-key cache-key}]
                  (swap! calls conj [:acquire context cache-key props (:id handle)])
                  handle))
     :release! (fn [handle]
                 (swap! calls conj [:release (:id handle)]))
     :update-layers! (fn [handle props]
                       (swap! calls conj [:update-layers (:id handle) props]))}))

(defn- children [widget]
  (:children @widget))

(defn- only-child [widget]
  (first (children widget)))

(defn- acquire-ids [calls]
  (vec (keep (fn [[op _ _ _ id]] (when (= :acquire op) id)) @calls)))

(defn- release-ids [calls]
  (vec (keep (fn [[op id]] (when (= :release op) id)) @calls)))

(deftest requiring-the-namespace-registers-the-complete-backend
  (is (= :slipstream (:name (b/current))))
  (is (every? fn? (map (b/current) [:create! :apply-props! :append-child!
                                    :remove-child! :replace-child! :reorder-child!
                                    :dispose! :schedule :run-lifecycle])))
  (let [ran (atom false)]
    (is (nil? (b/schedule #(reset! ran true))))
    (is @ran)))

(deftest mount-acquires-resources-and-unmount-recursively-releases-them
  (let [calls (atom [])]
    (binding [sg/*factory* (fake-factory calls)
              sg/*context* :headless]
      (let [scene (b/create! :scene {})
            root (ui/mount scene :scene
                           [:group {:id :root}
                            [:shape {:path :rectangle :color [1 0 0 1]}]])]
        (is (= #{:tag :props :children :context :resource :disposed?}
               (set (keys @scene))))
        (is (= [[:acquire :headless [:scene {}] {} 1]
                [:acquire :headless [:group {:id :root}] {:id :root} 2]
                [:acquire :headless [:shape {:path :rectangle}]
                {:path :rectangle :color [1 0 0 1]} 3]]
               @calls))
        (let [group (only-child scene)
              shape (only-child group)]
          (is (identical? shape (only-child (only-child scene))))
          (ui/unmount! root scene :scene)
          (is (= [] (children scene)))
          (resources/dispose-tree! group)
          (is (= [3 2] (release-ids calls)))
          (is (:disposed? @shape))
          (is (:disposed? @group))
          (resources/dispose-tree! group)
          (is (= [3 2] (release-ids calls)) "nested disposal is idempotent")
          (resources/dispose-tree! scene)
          (is (= [3 2 1] (release-ids calls))))))))

(deftest layer-updates-patch-the-widget-and-geometry-updates-replace-only-its-resource
  (let [calls (atom [])
        path (r/atom :first)
        color (r/atom [1 0 0 1])
        app (fn [] [:shape {:path @path :stroke 2 :color @color}])]
    (binding [sg/*factory* (fake-factory calls)
              sg/*context* :headless]
      (let [scene (b/create! :scene {})
            root (ui/mount scene :scene [app])
            shape (only-child scene)
            original-resource (:resource @shape)]
        (reset! color [0 1 0 1])
        (is (identical? shape (only-child scene)))
        (is (identical? original-resource (:resource @shape)))
        (is (= [:update-layers 2 {:path :first :stroke 2 :color [0 1 0 1]}]
               (last @calls)))
        (is (= [1 2] (acquire-ids calls)))
        (reset! path :second)
        (is (identical? shape (only-child scene)) "resource replacement preserves widget identity")
        (is (not (identical? original-resource (:resource @shape))))
        (is (= [1 2 3] (acquire-ids calls)))
        (is (= [2] (release-ids calls)))
        (is (= [:acquire :headless [:shape {:path :second :stroke 2}]
                {:path :second :stroke 2 :color [0 1 0 1]} 3]
               (nth @calls 3)))
        (is (= [:release 2] (nth @calls 4)))
        (ui/unmount! root scene :scene)
        (resources/dispose-tree! scene)
        (is (= [2 3 1] (release-ids calls)))))))

(deftest failed-geometry-replacement-keeps-the-old-resource-until-cleanup
  (let [calls (atom [])
        broken? (r/atom false)
        app (fn [] [:shape {:path (if @broken? :broken :working)
                            :fail-acquire? @broken?}])]
    (binding [sg/*factory* (fake-factory calls)
              sg/*context* :headless]
      (let [scene (b/create! :scene {})
            root (ui/mount scene :scene [app])
            shape (only-child scene)
            old-resource (:resource @shape)
            old-props (:props @shape)]
        (is (thrown-with-msg? Exception #"injected acquire failure" (reset! broken? true)))
        (is (identical? old-resource (:resource @shape)))
        (is (= old-props (:props @shape)))
        (is (= [] (release-ids calls)) "the last good resource survives error propagation")
        (ui/unmount! root scene :scene)
        (resources/dispose-tree! scene)
        (is (= [2 1] (release-ids calls)))))))

(deftest child-insertion-removal-and-tag-replacement-have-exact-ownership-calls
  (let [calls (atom [])
        view (r/atom [:shape])
        app (fn []
              (into [:group {}]
                    (for [tag @view]
                      (case tag
                        :shape [:shape {:path :rectangle}]
                        :text [:text {:text "hello"}]))))]
    (binding [sg/*factory* (fake-factory calls)
              sg/*context* :headless]
      (let [scene (b/create! :scene {})
            root (ui/mount scene :scene [app])
            group (only-child scene)
            original-shape (only-child group)]
        (reset! view [:shape :text])
        (is (= 2 (count (children group))))
        (is (identical? original-shape (first (children group))))
        (is (= [1 2 3 4] (acquire-ids calls)))
        (reset! view [:text])
        (is (= [:text] (mapv #(:tag @%) (children group))))
        (is (= [4 3] (release-ids calls))
            "position zero is replaced before the surplus old text is removed")
        (let [text (only-child group)]
          (reset! view [:shape])
          (is (= [:shape] (mapv #(:tag @%) (children group))))
          (is (not (identical? text (only-child group))))
          (is (= [1 2 3 4 5 6] (acquire-ids calls)))
          (is (= [4 3 5] (release-ids calls))))
        (ui/unmount! root scene :scene)
        (resources/dispose-tree! scene)
        (is (= [4 3 5 6 2 1] (release-ids calls)))))))

(deftest keyed-reorder-preserves-widget-and-resource-identity
  (let [calls (atom [])
        order (r/atom [:a :b :c])
        app (fn [] (into [:group {}]
                         (for [k @order] [:shape {:key k :path k}])))]
    (binding [sg/*factory* (fake-factory calls)
              sg/*context* :headless]
      (let [scene (b/create! :scene {})
            root (ui/mount scene :scene [app])
            group (only-child scene)
            before (into {} (map (fn [w] [(:path (:props @w)) w]) (children group)))
            resources-before (into {} (map (fn [[k w]] [k (:resource @w)]) before))]
        (reset! order [:c :a :b])
        (is (= [:c :a :b] (mapv #(:path (:props @%)) (children group))))
        (doseq [[k w] before]
          (is (identical? w (get (into {} (map (fn [child] [(:path (:props @child)) child])
                                               (children group))) k)))
          (is (identical? (resources-before k) (:resource @w))))
        (is (= [1 2 3 4 5] (acquire-ids calls)))
        (is (= [] (release-ids calls)))
        (ui/unmount! root scene :scene)
        (resources/dispose-tree! scene)
        (is (= [5 3 4 2 1] (release-ids calls)))))))

(deftest failed-mount-releases-the-partially-built-tree
  (let [calls (atom [])]
    (binding [sg/*factory* (fake-factory calls)
              sg/*context* :headless]
      (let [scene (b/create! :scene {})]
        (is (thrown-with-msg? Exception #"injected acquire failure"
              (ui/mount scene :scene
                        [:group {}
                         [:shape {:path :working}]
                         [:shape {:path :broken :fail-acquire? true}]])))
        (is (= [] (children scene)))
        (is (= [1 2 3] (acquire-ids calls)))
        (is (= [3 2] (release-ids calls)))
        (resources/dispose-tree! scene)
        (is (= [3 2 1] (release-ids calls)))))))

(deftest form-2-ratom-rerender-reuses-the-widget-and-releases-only-the-old-resource
  (let [calls (atom [])
        mounts (atom 0)
        counter (fn []
                  (swap! mounts inc)
                  (let [n (r/atom 0)]
                    (fn [] [:text {:text (str "count " @n)
                                   :on-click #(swap! n inc)}])))]
    (binding [sg/*factory* (fake-factory calls)
              sg/*context* :headless]
      (let [scene (b/create! :scene {})
            root (ui/mount scene :scene [counter])
            text (only-child scene)]
        (is (= 1 @mounts))
        ((:on-click (:props @text)))
        (is (= 1 @mounts))
        (is (identical? text (only-child scene)))
        (is (= "count 1" (:text (:props @text))))
        (is (= [1 2 3] (acquire-ids calls)))
        (is (= [2] (release-ids calls)))
        (ui/unmount! root scene :scene)
        (resources/dispose-tree! scene)
        (is (= [2 3 1] (release-ids calls)))))))

(deftest run-lifecycle-unmounts-before-disposing-its-scene
  (let [calls (atom [])
        value (r/atom 0)
        app (fn [] [:text {:text (str @value)}])]
    (binding [sg/*factory* (fake-factory calls)
              sg/*context* :headless]
      (ui/run app)
      (is (= [1 2] (acquire-ids calls)))
      (is (= [2 1] (release-ids calls)))
      (reset! calls [])
      (reset! value 1)
      (is (= [] @calls) "no reactive callback runs after the scene is disposed"))))

(deftest release-is-idempotent
  (let [calls (atom [])
        factory (fake-factory calls)
        resource (resources/acquire! factory :headless [:shape {}] {})]
    (resources/release! resource)
    (resources/release! resource)
    (is (= [[:acquire :headless [:shape {}] {} 1] [:release 1]] @calls))))
