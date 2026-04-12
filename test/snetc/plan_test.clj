(ns snetc.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [snetc.plan :as plan]))

(deftest new-plan-test
  (testing "normalizes parent CIDR and starts with one leaf"
    (let [planner (plan/new-plan "10.0.1.5/16")]
      (is (= "10.0.0.0/16" (:parent planner)))
      (is (= ["10.0.0.0/16"] (plan/leaf-cidrs planner)))
      (is (= "10.0.0.0/16" (:cursor planner)))
      (is (true? (plan/validate-plan planner))))))

(deftest split-and-join-test
  (testing "splitting replaces one leaf with two child leaves"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/split-leaf "10.0.0.0/24"))]
      (is (= ["10.0.0.0/25" "10.0.0.128/25"] (plan/leaf-cidrs planner)))
      (is (= "10.0.0.0/25" (:cursor planner)))
      (is (true? (plan/can-join? planner "10.0.0.0/25")))
      (is (true? (plan/validate-plan planner)))))

  (testing "joining sibling leaves restores the parent"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/split-leaf "10.0.0.0/24")
                      (plan/join-leaf "10.0.0.0/25"))]
      (is (= ["10.0.0.0/24"] (plan/leaf-cidrs planner)))
      (is (= "10.0.0.0/24" (:cursor planner)))
      (is (true? (plan/validate-plan planner)))))

  (testing "/32 leaves cannot be split"
    (let [planner (plan/new-plan "10.0.0.1/32")]
      (is (false? (plan/can-split? planner "10.0.0.1/32")))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Cannot split"
            (plan/split-leaf planner "10.0.0.1/32"))))))

(deftest nested-plan-test
  (testing "nested splits preserve sorted leaf order and depth"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/split-leaf "10.0.0.0/24")
                      (plan/split-leaf "10.0.0.0/25"))
          leaves (plan/leaves planner)]
      (is (= ["10.0.0.0/26" "10.0.0.64/26" "10.0.0.128/25"]
             (mapv :cidr leaves)))
      (is (= [2 2 1] (mapv :depth leaves)))
      (is (true? (plan/validate-plan planner))))))

(deftest label-test
  (testing "labels are stored on selected subnets and can be cleared"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/label-leaf "10.0.0.0/24" "edge")
                      (plan/label-leaf "10.0.0.0/24" ""))]
      (is (nil? (:label (plan/find-node planner "10.0.0.0/24"))))))

  (testing "a parent label survives split and join"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/label-leaf "10.0.0.0/24" "core")
                      (plan/split-leaf "10.0.0.0/24")
                      (plan/join-leaf "10.0.0.0/25"))]
      (is (= "core" (:label (plan/find-node planner "10.0.0.0/24")))))))

(deftest undo-redo-test
  (testing "undo and redo move between plan snapshots"
    (let [start (plan/new-plan "10.0.0.0/24")
          split (plan/split-leaf start "10.0.0.0/24")
          undone (plan/undo split)
          redone (plan/redo undone)]
      (is (= ["10.0.0.0/24"] (plan/leaf-cidrs undone)))
      (is (= ["10.0.0.0/25" "10.0.0.128/25"] (plan/leaf-cidrs redone))))))

(deftest export-import-test
  (testing "exported plans round trip through import-plan"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/split-leaf "10.0.0.0/24")
                      (plan/label-leaf "10.0.0.0/25" "web"))
          imported (plan/import-plan (plan/export-plan planner))]
      (is (= (plan/leaf-cidrs planner) (plan/leaf-cidrs imported)))
      (is (= "web" (:label (plan/find-node imported "10.0.0.0/25"))))
      (is (true? (plan/validate-plan imported))))))
