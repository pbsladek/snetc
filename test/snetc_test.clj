(ns snetc-test  (:require
                 [clojure.test :refer [deftest is]]
                 [snetc :refer [inet-aton
                                inet-ntoa
                                network-address
                                useable-range
                                full-range]]))

(def network "192.168.0.0")
(def network-int -1062731776)
(def mask 22)
(def used-range "192.168.0.1 - 192.168.3.254")
(def total-range "192.168.0.0 - 192.168.3.255")

(deftest inet-aton-test
  (is (= network-int (inet-aton network))))

(deftest inet-ntoa-test
  (is (= network (inet-ntoa network-int))))

(deftest network-address-test
  (is (= network-int (network-address network-int mask))))

(deftest range-test
  (is (= total-range (full-range network mask))))

(deftest useable-range-test
  (is (= used-range (useable-range network mask))))