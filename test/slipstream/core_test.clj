(ns slipstream.core-test
  (:require [clojure.test :refer [deftest is]]
            [slipstream.core :as slug]))

(defn- capture-ex-info [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      e)))

(deftest atlas-creation-and-build
  (let [atlas (atom nil)]
    (slug/with-atlas [a {:texture-width 64}]
      (reset! atlas a)
      (is (false? (slug/built? a)))
      (slug/build! a)
      (is (true? (slug/built? a))))
    (is (true? @(:destroyed? @atlas)))))

(deftest built-native-failure-throws-ex-info
  (let [atlas (slug/make-atlas)]
    (try
      (with-redefs [slipstream.ffi/atlas-built? (constantly false)
                    slipstream.ffi/last-error (constantly "injected native failure")]
        (let [error (capture-ex-info #(slug/built? atlas))]
          (is (some? error))
          (is (= :built? (:op (ex-data error))))
          (is (= "injected native failure" (:native-error (ex-data error))))))
      (finally
        (slug/destroy! atlas)))))

(deftest destroy-is-idempotent
  (let [atlas (slug/make-atlas)]
    (slug/destroy! atlas)
    (slug/destroy! atlas)
    (is (true? @(:destroyed? atlas)))))

(deftest build-after-destroy-throws-ex-info
  (let [atlas (slug/make-atlas)]
    (slug/destroy! atlas)
    (let [error (capture-ex-info #(slug/build! atlas))]
      (is (some? error))
      (is (= :build! (:op (ex-data error)))))))

(deftest zero-texture-width-surfaces-native-error
  (let [error (capture-ex-info #(slug/make-atlas {:texture-width 0}))]
    (is (some? error))
    (is (= :make-atlas (:op (ex-data error))))
    (is (seq (:native-error (ex-data error))))))

(deftest with-atlas-cleans-up-on-exception
  (let [atlas (atom nil)]
    (is (thrown-with-msg? Exception #"boom"
          (slug/with-atlas [a {}]
            (reset! atlas a)
            (throw (ex-info "boom" {})))))
    (is (true? @(:destroyed? @atlas)))))
