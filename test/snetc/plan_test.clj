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
      (is (true? (plan/validate-plan imported)))))

  (testing "import-plan accepts EDN string"
    (let [planner (plan/new-plan "10.0.0.0/24")
          edn-str (pr-str (plan/export-plan planner))
          imported (plan/import-plan edn-str)]
      (is (= ["10.0.0.0/24"] (plan/leaf-cidrs imported)))))

  (testing "import-plan throws on unsupported version"
    (let [data {:version 999 :parent "10.0.0.0/24"
                :root {:cidr "10.0.0.0/24" :label nil :children nil}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported plan version"
                            (plan/import-plan data)))))

  (testing "import-plan throws when root does not match parent"
    (let [data {:version 1 :parent "10.0.0.0/24"
                :root {:cidr "10.0.0.0/16" :label nil :children nil}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"root does not match"
                            (plan/import-plan data))))))

(deftest split-once-test
  (testing "split-once returns two child CIDRs"
    (is (= ["10.0.0.0/25" "10.0.0.128/25"] (plan/split-once "10.0.0.0/24"))))

  (testing "split-once throws on /32"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot split /32"
                          (plan/split-once "10.0.0.1/32")))))

(deftest bulk-plan-ops-test
  (testing "split-leaf-to-prefix recursively splits a leaf"
    (let [planner (plan/split-leaf-to-prefix (plan/new-plan "10.0.0.0/24")
                                             "10.0.0.0/24"
                                             26)]
      (is (= ["10.0.0.0/26" "10.0.0.64/26" "10.0.0.128/26" "10.0.0.192/26"]
             (plan/leaf-cidrs planner)))
      (is (= 1 (count (:undo planner))))
      (is (= ["10.0.0.0/24"] (plan/leaf-cidrs (plan/undo planner))))
      (is (true? (plan/validate-plan planner)))))

  (testing "split-leaf-for-hosts chooses the tightest host-fitting prefix"
    (let [planner (plan/split-leaf-for-hosts (plan/new-plan "10.0.0.0/24")
                                             "10.0.0.0/24"
                                             62)]
      (is (= ["10.0.0.0/26" "10.0.0.64/26" "10.0.0.128/26" "10.0.0.192/26"]
             (plan/leaf-cidrs planner)))))

  (testing "join-leaf-to-prefix joins upward through sibling leaves"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/split-leaf-to-prefix "10.0.0.0/24" 25)
                      (plan/join-leaf-to-prefix "10.0.0.0/25" 24))]
      (is (= ["10.0.0.0/24"] (plan/leaf-cidrs planner))))))

(deftest plan-error-paths-test
  (testing "join-leaf throws when cidr cannot be joined"
    (let [planner (plan/new-plan "10.0.0.0/24")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot join"
                            (plan/join-leaf planner "10.0.0.0/24")))))

  (testing "label-leaf throws on unknown cidr"
    (let [planner (plan/new-plan "10.0.0.0/24")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subnet"
                            (plan/label-leaf planner "192.168.0.0/24" "x")))))

  (testing "undo on fresh plan returns plan unchanged"
    (let [planner (plan/new-plan "10.0.0.0/24")]
      (is (= (plan/leaf-cidrs planner) (plan/leaf-cidrs (plan/undo planner))))))

  (testing "redo when nothing to redo returns plan unchanged"
    (let [planner (plan/new-plan "10.0.0.0/24")
          split   (plan/split-leaf planner "10.0.0.0/24")]
      (is (= (plan/leaf-cidrs split) (plan/leaf-cidrs (plan/redo split)))))))

(deftest bulk-split-limit-test
  (testing "the limit is inclusive and can be checked without allocating a tree"
    (is (= 65536 (plan/split-leaf-count "10.0.0.0/16" 32)))
    (is (= 1 (plan/split-leaf-count "10.0.0.1/32" 32))))
  (testing "oversized splits are rejected before tree construction"
    (with-redefs-fn {#'plan/split-tree-to-prefix
                    (fn [& _] (throw (AssertionError. "Tree allocation must not start")))}
      (fn []
        (doseq [[cidr prefix] [["0.0.0.0/0" 32] ["10.0.0.0/15" 32]]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"limit is 65536"
                                (plan/split-leaf-to-prefix (plan/new-plan cidr) cidr prefix)))))))
  (testing "invalid prefixes fail before bit arithmetic"
    (doseq [prefix [nil 24.5 "24" -1 33 64]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Target prefix must be"
                            (plan/split-leaf-count "10.0.0.0/24" prefix)))))
  (testing "smaller target prefixes are rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"smaller than selected"
                          (plan/split-leaf-count "10.0.0.0/24" 23)))))

(deftest imported-tree-validation-test
  (let [valid (-> (plan/new-plan "10.0.0.0/24")
                  (plan/split-leaf "10.0.0.0/24")
                  plan/export-plan)
        leaf {:cidr "10.0.0.0/24" :label nil :children nil}]
    (testing "malformed nodes and branches are rejected"
      (doseq [root [nil []
                    (assoc leaf :cidr nil)
                    (assoc leaf :label 42)
                    (assoc leaf :children "invalid")
                    (assoc leaf :children [nil nil])
                    (assoc leaf :children [leaf])
                    (assoc leaf :children [{:cidr "invalid" :children (:children (:root valid))}])
                    (assoc-in (:root valid) [:children 0 :cidr] "10.0.0.1/25")
                    (assoc-in (:root valid) [:children 0 :cidr] "10.0.0.0/26")
                    (assoc-in (:root valid) [:children 1 :cidr] "10.0.0.0/25")
                    (update (:root valid) :children #(vec (reverse %)))]]
        (is (thrown? clojure.lang.ExceptionInfo (plan/import-plan (assoc valid :root root)))
            (pr-str root))))
    (testing "valid leaves cannot conceal an invalid internal CIDR"
      (let [nested (-> (plan/import-plan valid)
                       (plan/split-leaf "10.0.0.0/25")
                       plan/export-plan)]
        (doseq [cidr ["invalid" "10.0.0.0/26"]]
          (let [bad (assoc-in nested [:root :children 0 :cidr] cidr)]
            (is (= ["10.0.0.0/26" "10.0.0.64/26" "10.0.0.128/25"]
                   (plan/leaf-cidrs bad)))
            (is (thrown? clojure.lang.ExceptionInfo (plan/import-plan bad)))))))
    (testing "a /32 cannot contain child nodes"
      (let [data (plan/export-plan (plan/new-plan "10.0.0.1/32"))
            node (:root data)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"/32 node cannot have children"
                              (plan/import-plan (assoc-in data [:root :children] [node node]))))))
    (testing "nested valid imports preserve split, join, and labels"
      (let [data (-> (plan/import-plan valid)
                     (plan/split-leaf "10.0.0.0/25")
                     (plan/label-leaf "10.0.0.0/26" "edge")
                     plan/export-plan)
            imported (plan/import-plan data)]
        (is (= data (plan/export-plan imported)))
        (is (plan/validate-plan (plan/join-leaf imported "10.0.0.0/26"))))))
  (testing "non-map data produces a contextual error"
    (doseq [data [nil [] 42]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Plan must be a map"
                            (plan/import-plan data))))))
