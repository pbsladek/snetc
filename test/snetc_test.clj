(ns snetc-test  (:require
                 [clojure.test :refer [deftest is]]
                 [snetc :refer [inet-aton, inet-ntoa, network-address]]))

(def network "192.168.0.0")
(def network-int "-1062731776")
(def mask 22)

(deftest inet-ntoa
  (is (= network-int (inet-aton network))))

(deftest inet-ntoa
  (is (= network (inet-ntoa network-int))))

(deftest network-address
  (is (= network-int (network-address network-int mask))))