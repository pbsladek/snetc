(ns snetc-test  (:require
                 [clojure.test :refer [deftest is]]
                 [snetc :refer [inet-aton, inet-ntoa, network-address]]))

(def network "192.168.0.0")
(def network-int -1062731776)
(def mask 22)

(deftest inet-aton-test
  (is (= network-int (inet-aton network))))

(deftest inet-ntoa-test
  (is (= network (inet-ntoa network-int))))

(deftest network-address-test
  (is (= network-int (network-address network-int mask))))