(ns slipstream.example.glfw
  (:require [clojure.string :as str]
            [slipstream.glfw :as glfw]))

(def usage-text
  (str "Usage: slipstream.example.glfw <font.ttf> [--curve-precision 16|32]\n"
       "  --curve-precision: curve texture float precision (DEFAULT 32). 16 halves\n"
       "    curve-texture memory but is a real precision tradeoff -- can visibly\n"
       "    degrade shape edges under heavy zoom.\n"))

(defn parse-args [args]
  (if (empty? args)
    {:error usage-text}
    (let [parsed
          (loop [remaining (vec (rest args))
                 result {:font-path (first args) :curve-precision "32"
                         :capture-path nil :max-frames nil}]
            (if (empty? remaining)
              result
              (case (first remaining)
                "--curve-precision"
                (recur (vec (drop 2 remaining))
                       (assoc result :curve-precision (second remaining)))
                "--capture"
                (recur (vec (drop 2 remaining))
                       (assoc result :capture-path (second remaining)))
                "--frames"
                (recur (vec (drop 2 remaining))
                       (assoc result :max-frames (some-> (second remaining) parse-long)))
                (recur (vec (rest remaining)) result))))]
      (if (contains? #{"16" "32"} (:curve-precision parsed))
        (update parsed :curve-precision parse-long)
        {:error (str "Invalid --curve-precision: " (:curve-precision parsed)
                     " (expected 16 or 32)\n")}))))

(defn select-text [piped]
  (let [text (some-> piped (str/replace #"[\r\n]+$" ""))]
    (if (seq text) text "slughorn")))

(defn scene-data [font-path text curve-precision]
  (let [codepoints (mapv #(bit-and (int %) 0xff) (.getBytes text "UTF-8"))]
    [:scene {:curve-precision curve-precision}
     [:font {:id :example-font :path font-path :codepoints codepoints}]
     [:text {:font :example-font :size 1 :at [0 0] :color [1 1 1 1]
             :compatibility true}
      text]]))

(defn- exit [code]
  (System/exit code))

(defn -main [& args]
  (let [{:keys [error font-path curve-precision capture-path max-frames]}
        (parse-args args)]
    (if error
      (do
        (binding [*out* *err*] (print error))
        (exit 1))
      (try
        (let [text (select-text (glfw/read-stdin-text))]
          (glfw/run! (scene-data font-path text curve-precision)
                     {:title "slughorn" :capture-path capture-path
                      :max-frames max-frames}))
        (exit 0)
        (catch Throwable error
          (binding [*out* *err*] (println (ex-message error)))
          (exit 1))))))
