(ns snetc.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [snetc.core :as core]
            [snetc.tui :as tui]))

(defn- exiting-die [msg]
  (throw (ex-info msg {:exit 1})))

(defn- dies-with? [f pattern]
  (try
    (with-redefs-fn {#'core/die exiting-die} f)
    false
    (catch clojure.lang.ExceptionInfo e
      (and (= 1 (:exit (ex-data e)))
           (boolean (re-find pattern (ex-message e)))))))

(deftest handler-validation-test
  (testing "classify requires at least one input"
    (is (dies-with? #(#'core/handle-classify []) #"classify requires")))

  (testing "overlaps requires at least two CIDRs"
    (is (dies-with? #(#'core/handle-overlaps []) #"overlaps requires"))
    (is (dies-with? #(#'core/handle-overlaps ["10.0.0.0/24"]) #"overlaps requires")))

  (testing "free requires at least one allocated CIDR"
    (is (dies-with? #(#'core/handle-free ["10.0.0.0/24"]) #"free requires")))

  (testing "interactive tree requires exactly one parent CIDR"
    (is (dies-with? #(#'core/handle-interactive-tree []) #"tree requires"))
    (is (dies-with? #(#'core/handle-interactive-tree ["10.0.0.0/24" "extra"]) #"exactly one"))))

(deftest interactive-tree-handler-test
  (testing "interactive tree delegates to the TUI"
    (let [called (atom nil)]
      (with-redefs-fn {#'tui/run-tree! #(reset! called %)}
        #(#'core/handle-interactive-tree ["10.0.0.0/24"]))
      (is (= "10.0.0.0/24" @called)))))
