(ns slipstream.example.demoscene
  (:require [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [jolt.nrepl :as nrepl]
            [slipstream.example.demoscene-shader :as visuals]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-renderer :as renderer])
  (:import [java.io File FileOutputStream]))

(def ^:private rich-middleware
  '[nrepl.middleware/default-middleware cider.nrepl/cider-middleware])

(defonce state
  (r/atom visuals/initial-state))

(defonce ^:private lifecycle (atom nil))

(defn app
  ([font-path] (app font-path (:aspect @state) (:bounds @state)))
  ([font-path aspect] (app font-path aspect [0 aspect 0 1]))
  ([font-path aspect bounds]
   (visuals/scene-for-state
     (assoc @state :font font-path :aspect aspect :bounds bounds))))

(defn- advance-from-native-input! [previous-frame bounds]
  (when-let [context (sg/current-context)]
    (let [now (System/nanoTime)
          previous @previous-frame
          dt (if previous (/ (- now previous) 1000000000.0) 0.0)]
      (reset! previous-frame now)
      (let [input (renderer/input-snapshot context)
            mouse (renderer/cursor->bounds context bounds)]
        (swap! state visuals/update-state dt (assoc input :mouse mouse))))))

(defn- write-ppm! [path {:keys [width height pixels]}]
  (let [rgb (byte-array (* width height 3))]
    (doseq [y (range height)
            x (range width)]
      (let [source (* 4 (+ (* (- height 1 y) width) x))
            target (* 3 (+ (* y width) x))]
        (doseq [channel (range 3)]
          (aset rgb (+ target channel) (aget pixels (+ source channel))))))
    (with-open [out (FileOutputStream. path)]
      (.write out (.getBytes (str "P6\n" width " " height "\n255\n") "UTF-8"))
      (.write out rgb))))

(defn- maybe-capture! [capture-dir capture-state]
  (when capture-dir
    (let [{:keys [index captured-at]} @capture-state
          now (System/currentTimeMillis)]
      (when (or (zero? index) (>= (- now captured-at) 2000))
        (when-let [context (sg/current-context)]
          (let [frame (renderer/capture! context)
                next-index (inc index)
                directory (File. (str capture-dir))]
            (.mkdirs directory)
            (write-ppm! (File. directory (format "frame-%06d.ppm" next-index)) frame)
            (reset! capture-state {:index next-index :captured-at now})))))))

(defn- stop-server! [{:keys [server-stopped? stop-server]}]
  (when (compare-and-set! server-stopped? false true)
    (stop-server))
  nil)

(defn- request-native-close! []
  (when-let [context (sg/current-context)]
    (renderer/request-close! context)))

(defn stop! []
  (when-let [{:keys [close-requested?]} @lifecycle]
    (when (compare-and-set! close-requested? false true)
      (ui/on-gui request-native-close!)))
  nil)

(defn- cleanup-run! [run primary-error]
  (try
    (stop-server! run)
    (catch Throwable cleanup-error
      (if-let [primary @primary-error]
        (binding [*out* *err*]
          (println (str "Demoscene cleanup failed after " (ex-message primary)
                        ": " (ex-message cleanup-error))
                   (ex-data cleanup-error)))
        (throw cleanup-error)))
    (finally
      (reset! lifecycle nil))))

(defn- run-with-cleanup [run work]
  (let [primary-error (atom nil)]
    (try
      (work)
      (catch Throwable error
        (reset! primary-error error)
        (throw error))
      (finally
        (cleanup-run! run primary-error)))))

(defn start!
  [& [{:keys [font-path port width height bounds capture-dir max-frames auto-quit-ms]}]]
  (when-not font-path
    (throw (ex-info "A TTF/OTF font path is required" {:option :font-path})))
  (let [run (locking lifecycle
              (if-let [existing @lifecycle]
                existing
                (let [stop-server (nrepl/start (if (nil? port) 7888 port)
                                               rich-middleware {})
                      actual-port (or (:port (meta stop-server)) port)
                      run {:port actual-port
                           :stop-server stop-server
                           :server-stopped? (atom false)
                           :close-requested? (atom false)}]
                  (try
                    (reset! lifecycle run)
                    (assoc run :owner? true)
                    (catch Throwable error
                      (cleanup-run! run (atom error))
                      (throw error))))))]
    (if-not (:owner? run)
      {:port (:port run)}
      (run-with-cleanup
        run
        (fn []
          (println (str "Demoscene nREPL: 127.0.0.1:" (:port run)
                        " — connect your editor"))
          (let [width (or width 960)
                height (or height 640)
                aspect (if bounds
                         (/ (- (nth bounds 1) (nth bounds 0))
                            (- (nth bounds 3) (nth bounds 2)))
                         (/ (double width) height))
                scene-bounds (or bounds [0 aspect 0 1])
                previous-frame (atom nil)
                capture-state (atom {:index 0 :captured-at 0})]
            (swap! state assoc :aspect aspect :bounds scene-bounds
                   :nrepl-port (:port run))
            (ui/run #(app font-path aspect scene-bounds)
                    :title "Slipstream demoscene"
                    :width width
                    :height height
                    :bounds bounds
                    :on-start (fn []
                                (when @(:close-requested? run)
                                  (request-native-close!)))
                    :after-render #(do
                                     (advance-from-native-input! previous-frame scene-bounds)
                                     (maybe-capture! capture-dir capture-state))
                    :max-frames max-frames
                    :auto-quit-ms auto-quit-ms))
          {:port (:port run)})))))

(def usage-text
  (str "Usage: slipstream.example.demoscene <font.ttf> [--port N] "
       "[--capture-dir PATH] [--max-frames N] [--auto-quit-ms N]\n"))

(defn- option-integer [option value]
  (or (some-> value parse-long)
      (throw (ex-info (str option " requires an integer") {:option option :value value}))))

(defn parse-args [args]
  (if (empty? args)
    {:error usage-text}
    (loop [remaining (vec (rest args))
           result {:font-path (first args)
                   :port 7888
                   :capture-dir nil
                   :max-frames nil
                   :auto-quit-ms nil}]
      (if (empty? remaining)
        result
        (let [[option value] remaining]
          (case option
            "--port" (recur (vec (drop 2 remaining))
                            (assoc result :port (option-integer option value)))
            "--capture-dir" (if value
                              (recur (vec (drop 2 remaining))
                                     (assoc result :capture-dir value))
                              {:error (str "--capture-dir requires a path\n" usage-text)})
            "--max-frames" (recur (vec (drop 2 remaining))
                                  (assoc result :max-frames (option-integer option value)))
            "--auto-quit-ms" (recur (vec (drop 2 remaining))
                                    (assoc result :auto-quit-ms (option-integer option value)))
            {:error (str "Unknown option: " option "\n" usage-text)}))))))

(defn- exit [code]
  (System/exit code))

(defn -main [& args]
  (let [{:keys [error] :as options} (parse-args args)]
    (if error
      (do
        (binding [*out* *err*] (print error))
        (exit 1))
      (try
        (start! options)
        (exit 0)
        (catch Throwable error
          (binding [*out* *err*]
            (println (ex-message error) (ex-data error)))
          (exit 1))))))
