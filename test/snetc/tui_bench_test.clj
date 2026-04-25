(ns snetc.tui-bench-test
  (:require [clojure.test :refer [deftest is testing]]
            [snetc.tui-bench :as bench]))

(deftest lightweight-render-benchmark-test
  (testing "render benchmark returns comparable timing data"
    (let [sample (bench/render-sample 1000)]
      (is (= 1000 (:rows sample)))
      (is (pos? (:nanos sample)))
      (is (= #{:width :height :mode} (set (keys (:ret sample)))))))

  (testing "layout benchmark returns comparable timing data"
    (let [sample (bench/layout-sample 1000)]
      (is (= 1000 (:rows sample)))
      (is (pos? (:nanos sample)))
      (is (= #{:mode :label-width} (set (keys (:ret sample)))))))

  (testing "filter benchmark returns comparable timing data"
    (let [sample (bench/filter-sample 1000 "label:label-10")]
      (is (= 1000 (:rows sample)))
      (is (pos? (:nanos sample)))
      (is (pos? (:ret sample)))))

  (testing "diff benchmark returns output size and timing data"
    (let [sample (bench/diff-sample 1000)]
      (is (= 1000 (:rows sample)))
      (is (pos? (:nanos sample)))
      (is (pos? (:ret sample))))))
