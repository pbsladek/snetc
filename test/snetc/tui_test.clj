(ns snetc.tui-test
  (:require [clojure.test :refer [deftest is testing]]
            [snetc.plan :as plan]
            [snetc.tui :as tui]))

(def visual-calculator-leaves
  ["192.168.0.0/21"
   "192.168.8.0/21"
   "192.168.16.0/20"
   "192.168.32.0/20"
   "192.168.48.0/20"
   "192.168.64.0/19"
   "192.168.96.0/19"
   "192.168.128.0/19"
   "192.168.160.0/19"
   "192.168.192.0/19"
   "192.168.224.0/20"
   "192.168.240.0/21"
   "192.168.248.0/22"
   "192.168.252.0/23"
   "192.168.254.0/23"])

(def visual-calculator-leaves-after-join
  ["192.168.0.0/21"
   "192.168.8.0/21"
   "192.168.16.0/20"
   "192.168.32.0/20"
   "192.168.48.0/20"
   "192.168.64.0/19"
   "192.168.96.0/19"
   "192.168.128.0/19"
   "192.168.160.0/19"
   "192.168.192.0/19"
   "192.168.224.0/20"
   "192.168.240.0/21"
   "192.168.248.0/22"
   "192.168.252.0/22"])

(defn- press [state key]
  (#'tui/handle-key state key nil))

(defn- press-all [state keys]
  (reduce press state keys))

(deftest visual-calculator-topology-test
  (testing "selection, split, and join can recreate the visual calculator example"
    (let [start {:plan (plan/new-plan "192.168.0.0/16")
                 :selected 0
                 :scroll 0
                 :message "Ready"}
          split-keys [:split :split :split :split :split
                      :down :down :down :split
                      :down :down :split
                      :down :down :split
                      :split
                      :down :down :split
                      :down :split
                      :down :split
                      :down :split
                      :down :split]
          split-state (press-all start split-keys)
          joined-state (press split-state :join)
          restored-state (press joined-state :split)]
      (is (= visual-calculator-leaves
             (plan/leaf-cidrs (:plan split-state))))
      (is (= visual-calculator-leaves-after-join
             (plan/leaf-cidrs (:plan joined-state))))
      (is (= visual-calculator-leaves
             (plan/leaf-cidrs (:plan restored-state))))
      (is (true? (plan/validate-plan (:plan restored-state)))))))
