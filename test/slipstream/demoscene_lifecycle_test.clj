(ns slipstream.demoscene-lifecycle-test
  (:require [clojure.test :refer [deftest is]]
            [glimmer.core :as ui]
            [jolt.nrepl :as jolt-nrepl]
            [nrepl.core :as nrepl]
            [slipstream.example.demoscene :as demo]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-renderer :as renderer]))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT for demoscene lifecycle tests" {}))))

(def ^:private rich-middleware
  '[nrepl.middleware/default-middleware cider.nrepl/cider-middleware])

(defn- fake-stop [events port]
  (with-meta
    (fn [] (swap! events conj :stop))
    {:port port :port-file nil}))

(deftest start-is-idempotent-starts-nrepl-first-and-has-unlimited-production-defaults
  (let [events (atom [])
        starts (atom 0)
        caller (Thread/currentThread)
        persistent-state demo/state]
    (is (nil? (demo/stop!)) "stop before start is harmless")
    (with-redefs [jolt-nrepl/start
                  (fn [port middleware _opts]
                    (swap! starts inc)
                    (is (= 7888 port))
                    (is (= rich-middleware middleware))
                    (swap! events conj :server)
                    (fake-stop events 7888))
                  ui/run
                  (fn [root-fn & {:as opts}]
                    (swap! events conj :ui)
                    (is (fn? root-fn))
                    (is (identical? caller (Thread/currentThread)))
                    (is (nil? (:max-frames opts)))
                    (is (nil? (:auto-quit-ms opts)))
                    (is (= {:port 7888} (demo/start! {:font-path test-font})))
                    (is (= 1 @starts) "a nested start reuses the running application"))]
      (is (= {:port 7888} (demo/start! {:font-path test-font}))))
    (is (= [:server :ui :stop] @events))
    (is (nil? (demo/stop!)))
    (is (= [:server :ui :stop] @events) "repeated stop does not stop the server twice")
    (is (identical? persistent-state demo/state))))

(deftest start-reports-the-os-assigned-port-from-stop-metadata
  (let [events (atom [])]
    (with-redefs [jolt-nrepl/start
                  (fn [port _middleware _opts]
                    (is (zero? port))
                    (fake-stop events 45678))
                  ui/run (fn [& _] nil)]
      (let [output (with-out-str
                     (is (= {:port 45678}
                            (demo/start! {:font-path test-font :port 0}))))]
        (is (re-find #"45678" output))))
    (is (= [:stop] @events))))

(deftest nrepl-startup-failure-prevents-ui-and-preserves-the-original-error
  (let [failure (ex-info "injected nREPL startup failure" {:stage :nrepl})
        ui-started? (atom false)]
    (with-redefs [jolt-nrepl/start (fn [& _] (throw failure))
                  ui/run (fn [& _] (reset! ui-started? true))]
      (let [actual (try
                     (demo/start! {:font-path test-font})
                     nil
                     (catch Throwable error error))]
        (is (identical? failure actual))))
    (is (false? @ui-started?))
    (is (nil? (demo/stop!)))))

(deftest ui-failure-stops-nrepl-once-and-allows-a-clean-restart
  (let [events (atom [])
        starts (atom 0)
        fail-ui? (atom true)
        failure (ex-info "injected UI startup failure" {:stage :ui})]
    (with-redefs [jolt-nrepl/start
                  (fn [& _]
                    (swap! starts inc)
                    (fake-stop events 7888))
                  ui/run
                  (fn [& _]
                    (when @fail-ui? (throw failure)))]
      (let [actual (try
                     (demo/start! {:font-path test-font})
                     nil
                     (catch Throwable error error))]
        (is (identical? failure actual)))
      (is (= [:stop] @events))
      (demo/stop!)
      (is (= [:stop] @events))
      (reset! fail-ui? false)
      (is (= {:port 7888} (demo/start! {:font-path test-font}))))
    (is (= 2 @starts))
    (is (= [:stop :stop] @events))))

(deftest stop-schedules-one-native-close-and-defers-server-stop-to-ui-teardown
  (let [events (atom [])]
    (with-redefs [jolt-nrepl/start
                  (fn [& _]
                    (swap! events conj :server)
                    (fake-stop events 7888))
                  sg/current-context (constantly :native-context)
                  ui/on-gui (fn [work]
                              (swap! events conj :scheduled)
                              (work))
                  renderer/request-close!
                  (fn [context]
                    (is (= :native-context context))
                    (swap! events conj :close))
                  ui/run
                  (fn [& _]
                    (swap! events conj :ui)
                    (is (nil? (demo/stop!)))
                    (is (nil? (demo/stop!)))
                    (is (= [:server :ui :scheduled :close] @events)
                        "the server remains available until the UI has torn down"))]
      (demo/start! {:font-path test-font}))
    (is (= [:server :ui :scheduled :close :stop] @events))))

(deftest close-requested-before-the-ui-loop-is-honored-on-start
  (let [real-run ui/run
        events (atom [])
        closes (atom 0)]
    (with-redefs [jolt-nrepl/start (fn [& _] (fake-stop events 7888))
                  renderer/request-close! (fn [_] (swap! closes inc))
                  ui/run
                  (fn [root-fn & {:as opts}]
                    (demo/stop!)
                    (is (fn? (:on-start opts)))
                    (apply real-run root-fn
                           (mapcat identity (assoc opts :visible false :max-frames 1))))]
      (demo/start! {:font-path test-font :width 320 :height 240}))
    (is (= 1 @closes))
    (is (= [:stop] @events))))

(deftest close-during-scheduled-work-tears-down-and-drops-late-native-work
  (let [real-run ui/run
        events (atom [])
        context (atom nil)
        late-work? (atom false)]
    (with-redefs [jolt-nrepl/start (fn [& _] (fake-stop events 7888))
                  ui/run
                  (fn [root-fn & {:as opts}]
                    (let [opts (assoc opts :visible false :max-frames 30
                                      :on-start
                                      (fn []
                                        (reset! context (sg/current-context))
                                        (ui/on-gui
                                          (fn []
                                            (swap! events conj :stop-requested)
                                            (demo/stop!)))))]
                      (apply real-run root-fn (mapcat identity opts))))]
      (is (= {:port 7888}
             (demo/start! {:font-path test-font :width 320 :height 240}))))
    (is (= [:stop-requested :stop] @events))
    (is (some? @context))
    (renderer/schedule! @context #(reset! late-work? true))
    (is (false? @late-work?))))

(deftest rich-nrepl-server-supports-a-stateful-session-eval
  (let [real-start jolt-nrepl/start
        actual-port (atom nil)]
    (with-redefs [jolt-nrepl/start
                  (fn [port middleware opts]
                    (let [stop (real-start port middleware (assoc opts :port-file nil))]
                      (reset! actual-port (:port (meta stop)))
                      stop))
                  ui/run
                  (fn [& _]
                    (let [transport (nrepl/connect "127.0.0.1" @actual-port)]
                      (try
                        (let [session (nrepl/new-session transport)
                              values (nrepl/response-values
                                       (nrepl/message
                                         transport
                                         {:op "eval" :session session
                                          :code "(do (swap! slipstream.example.demoscene/state assoc :lifecycle-eval 42) (:lifecycle-eval @slipstream.example.demoscene/state))"}))]
                          (is (= [42] values)))
                        (finally
                          (nrepl/close transport)))))]
      (is (pos? (:port (demo/start! {:font-path test-font :port 0}))))
      (is (pos? @actual-port))
      (is (= 42 (:lifecycle-eval @demo/state))))))

(deftest cli-requires-a-font-and-only-adds-limits-when-requested
  (is (string? (:error (demo/parse-args []))))
  (is (= {:font-path test-font :port 7888 :capture-dir nil
          :max-frames nil :auto-quit-ms nil}
         (demo/parse-args [test-font])))
  (is (= {:font-path test-font :port 0 :capture-dir "native/build/captures"
          :max-frames 2 :auto-quit-ms 15}
         (demo/parse-args [test-font "--port" "0"
                           "--capture-dir" "native/build/captures"
                           "--max-frames" "2" "--auto-quit-ms" "15"]))))
