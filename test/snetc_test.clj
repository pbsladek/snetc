(ns snetc-test  (:require [clojure.test :refer :all]
            [snetc :refer :all]))

(deftest adding-numbers
  (is (= 4 (plus 2 2))))

(deftest dividing-numbers
  (is (= 2 (divide 4 2))))

(deftest dividing-numbers-by-zero
  (is (thrown? ArithmeticException (divide 1 0))))