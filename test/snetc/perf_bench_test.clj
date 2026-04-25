(ns snetc.perf-bench-test
  (:require [clojure.test :refer [deftest is testing]]
            [snetc.perf-bench :as bench]))

(deftest run-suite-test
  (testing "performance probes return stable comparable result shapes"
    (let [results (bench/run-suite {:size 16 :iterations 1})]
      (is (= ["ops/find-overlaps"
              "ops/longest-prefix-match"
              "ops/aggregate"
              "plan/split-leaf-to-prefix"
              "tui/filter-rows"
              "tui/render-frame"]
             (mapv :name results)))
      (is (every? #(= 1 (:iterations %)) results))
      (is (every? #(pos? (:total-nanos %)) results))
      (is (every? #(contains? % :result) results)))))
