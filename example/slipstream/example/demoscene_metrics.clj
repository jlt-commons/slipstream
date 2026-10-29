(ns slipstream.example.demoscene-metrics)

(def ^:private counter-keys
  [:shader-compilations :resource-uploads :paint-texture-uploads])

(def ^:private current-keys
  [:framebuffer-size :live-resources :live-effects
   :draw-commands :geometry-commands :group-commands])

(defn- counters [frame-state]
  (select-keys frame-state counter-keys))

(defn- counter-deltas [current baseline]
  (into {}
        (map (fn [key]
               [key (if baseline (- (get current key 0) (get baseline key 0)) 0)]))
        counter-keys))

(defn- percentile [sorted-values fraction]
  (if (empty? sorted-values)
    0.0
    (let [rank (long (Math/ceil (* fraction (count sorted-values))))]
      (double (nth sorted-values (max 0 (dec rank)))))))

(defn- duration-stats [durations]
  (let [count (count durations)
        sorted (vec (sort durations))
        mean (if (zero? count) 0.0 (/ (double (reduce + durations)) count))]
    {:last-frame-ms (if (zero? count) 0.0 (double (peek durations)))
     :mean-frame-ms mean
     :fps (if (pos? mean) (/ 1000.0 mean) 0.0)
     :frame-percentiles-ms {:p50 (percentile sorted 0.50)
                            :p95 (percentile sorted 0.95)
                            :p99 (percentile sorted 0.99)}}))

(defn- bounded-conj [values value maximum]
  (let [values (conj values value)]
    (if (> (count values) maximum)
      (vec (drop (- (count values) maximum) values))
      values)))

(defn- hud-line [{:keys [durations current]}]
  (let [{:keys [fps last-frame-ms frame-percentiles-ms]} (duration-stats durations)]
    (format "FPS=%.1f frame=%.2fms p95=%.2fms draws=%d resources=%d effects=%d"
            (double fps)
            (double last-frame-ms)
            (double (:p95 frame-percentiles-ms))
            (long (get current :draw-commands 0))
            (long (get current :live-resources 0))
            (long (get current :live-effects 0)))))

(defn new-collector [options]
  (atom {:options options
         :warmup-ms (get options :warmup-ms 2000)
         :max-samples (get options :max-samples 600)
         :hud-interval-ms (max 250 (get options :hud-interval-ms 250))
         :started-at nil
         :previous-at nil
         :last-at nil
         :durations []
         :counter-baseline nil
         :cumulative-counters {}
         :current {}
         :hud-text ""
         :hud-updated-at nil}))

(defn sample-frame! [collector frame-state now]
  (swap! collector
         (fn [{:keys [warmup-ms max-samples hud-interval-ms
                      started-at previous-at counter-baseline hud-updated-at]
               :as state}]
           (let [started-at (or started-at now)
                 warmed-up? (>= (- now started-at) warmup-ms)
                 measured? (and previous-at warmed-up?
                                  (>= (- previous-at started-at) warmup-ms))
                 durations (if measured?
                             (bounded-conj (:durations state)
                                           (max 0.0 (double (- now previous-at)))
                                           max-samples)
                             (:durations state))
                 baseline (if (and warmed-up? (nil? counter-baseline))
                            (counters frame-state)
                            counter-baseline)
                 next-state (assoc state
                                   :started-at started-at
                                   :previous-at now
                                   :last-at now
                                   :durations durations
                                   :counter-baseline baseline
                                   :cumulative-counters (counters frame-state)
                                   :current (select-keys frame-state current-keys))
                 update-hud? (or (nil? hud-updated-at)
                                 (>= (- now hud-updated-at) hud-interval-ms))]
             (if update-hud?
               (assoc next-state :hud-text (hud-line next-state) :hud-updated-at now)
               next-state))))
  nil)

(defn metrics-snapshot [collector]
  (let [{:keys [options warmup-ms started-at last-at durations counter-baseline
                cumulative-counters current hud-text]} @collector
        elapsed-ms (if started-at (max 0.0 (double (- last-at started-at))) 0.0)]
    (merge {:warmup-ms warmup-ms
            :warming-up? (if started-at (< elapsed-ms warmup-ms) (pos? warmup-ms))
            :elapsed-ms elapsed-ms
            :sample-count (count durations)
            :cumulative-counters cumulative-counters
            :counter-deltas (counter-deltas cumulative-counters counter-baseline)
            :live-resources (get current :live-resources 0)
            :live-effects (get current :live-effects 0)
            :draw-commands (get current :draw-commands 0)
            :geometry-commands (get current :geometry-commands 0)
            :group-commands (get current :group-commands 0)
            :framebuffer-size (:framebuffer-size current)
            :profile (get options :profile :showcase)
            :intensity (get options :intensity 1)
            :options options
            :hud-text hud-text}
           (duration-stats durations))))

(defn reset-metrics! [collector]
  (swap! collector assoc
         :started-at nil
         :previous-at nil
         :last-at nil
         :durations []
         :counter-baseline nil
         :current {}
         :hud-text ""
         :hud-updated-at nil)
  nil)

(defn hud-status [collector]
  (:hud-text @collector))
