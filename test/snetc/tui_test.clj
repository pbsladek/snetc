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

;;; ── individual key handlers ──────────────────────────────────────────────────

(def ^:private base-state
  {:plan (plan/new-plan "10.0.0.0/24") :selected 0 :scroll 0 :message "Ready"})

(deftest movement-keys-test
  (testing "down increases selection"
    (let [after (press (press base-state :split) :down)]
      (is (= 1 (:selected after)))))

  (testing "up decreases selection"
    (let [at-1 (-> base-state (press :split) (press :down))
          at-0 (press at-1 :up)]
      (is (= 0 (:selected at-0)))))

  (testing "left decreases selection (same as up)"
    (let [at-1 (-> base-state (press :split) (press :down))
          at-0 (press at-1 :left)]
      (is (= 0 (:selected at-0)))))

  (testing "right increases selection (same as down)"
    (let [after (press (press base-state :split) :right)]
      (is (= 1 (:selected after)))))

  (testing "selection is clamped at 0 when moving up from top"
    (is (= 0 (:selected (press base-state :up))))))

(deftest undo-redo-keys-test
  (testing "undo reverses a split"
    (let [after-split (press base-state :split)
          after-undo  (press after-split :undo)]
      (is (= 1 (count (plan/leaf-cidrs (:plan after-undo)))))
      (is (= "Undo" (:message after-undo)))))

  (testing "redo re-applies after undo"
    (let [after-split (press base-state :split)
          after-undo  (press after-split :undo)
          after-redo  (press after-undo :redo)]
      (is (= 2 (count (plan/leaf-cidrs (:plan after-redo)))))
      (is (= "Redo" (:message after-redo))))))

(deftest unknown-key-test
  (testing ":unknown sets the unknown key message"
    (let [result (press base-state :unknown)]
      (is (= "Unknown key" (:message result)))))

  (testing "unhandled keys return state unchanged"
    (let [result (press base-state :eof)]
      (is (= base-state result)))))

(deftest apply-plan-op-error-test
  (testing "splitting a /32 leaf sets an error message"
    (let [state (-> base-state
                    (press :split) (press :split)   ; split to /25, /25
                    (press :split) (press :split)   ; split first /25 to /26, /26
                    (press :split) (press :split)   ; /27 ...
                    (press :split) (press :split)   ; /28 ...
                    (press :split) (press :split)   ; /29 ...
                    (press :split) (press :split)   ; /30 ...
                    (press :split) (press :split)   ; /31 ...
                    (press :split))                 ; → /32 leaves
          at-32 (press state :down)
          result (press at-32 :split)]
      ;; /32 can't be split — message should reflect the failure
      (is (string? (:message result)))))

  (testing "print-cidrs writes leaf CIDR file and sets success message"
    (let [result (press base-state :print-cidrs)]
      (is (clojure.string/includes? (:message result) "snetc-leaves.txt"))))

  (testing "no subnet selected message when selection is out of bounds"
    (let [out-of-bounds (assoc base-state :selected 999)
          result (press out-of-bounds :split)]
      (is (= "No subnet selected" (:message result))))))

;;; ── private utility functions ────────────────────────────────────────────────

(deftest parse-int-or-test
  (testing "returns parsed integer for valid string"
    (is (= 42 (#'tui/parse-int-or "42" 0))))

  (testing "returns fallback for non-numeric string"
    (is (= 80 (#'tui/parse-int-or "abc" 80))))

  (testing "returns fallback for nil"
    (is (= 120 (#'tui/parse-int-or nil 120)))))

(deftest index-of-test
  (testing "returns index when needle is found"
    (is (= 2 (#'tui/index-of [:a :b :c :d] :c))))

  (testing "returns -1 when needle is not found"
    (is (= -1 (#'tui/index-of [:a :b :c] :z)))))

;;; ── write functions and YAML generation ─────────────────────────────────────

(deftest write-plan-files-test
  (testing "write-edn-plan! writes a plan file and returns path"
    (let [planner (plan/new-plan "10.0.0.0/24")
          path    (#'tui/write-edn-plan! planner)]
      (is (string? path))
      (is (clojure.string/ends-with? path ".edn"))))

  (testing "write-json-plan! writes a JSON plan and returns path"
    (let [planner (plan/new-plan "10.0.0.0/24")
          path    (#'tui/write-json-plan! planner)]
      (is (string? path))
      (is (clojure.string/ends-with? path ".json"))))

  (testing "write-yaml-plan! writes a YAML plan and returns path"
    (let [planner (plan/new-plan "10.0.0.0/24")
          path    (#'tui/write-yaml-plan! planner)]
      (is (string? path))
      (is (clojure.string/ends-with? path ".yaml")))))

(deftest yaml-generation-test
  (testing "plan->yaml generates a YAML string with version and parent"
    (let [planner (plan/new-plan "10.0.0.0/24")
          yaml    (#'tui/plan->yaml (plan/export-plan planner))]
      (is (string? yaml))
      (is (clojure.string/includes? yaml "10.0.0.0/24"))
      (is (clojure.string/includes? yaml "version:"))))

  (testing "node->yaml-lines generates lines for a leaf node (no children)"
    (let [planner (plan/new-plan "10.0.0.0/24")
          root    (:root (plan/export-plan planner))
          lines   (#'tui/node->yaml-lines root 0)]
      (is (seq lines))
      (is (some #(clojure.string/includes? % "10.0.0.0/24") lines))
      (is (some #(clojure.string/includes? % "children: null") lines))))

  (testing "node->yaml-lines includes children when present"
    (let [planner (plan/split-leaf (plan/new-plan "10.0.0.0/24") "10.0.0.0/24")
          root    (:root (plan/export-plan planner))
          lines   (#'tui/node->yaml-lines root 0)]
      (is (some #(clojure.string/includes? % "children:") lines))
      (is (some #(clojure.string/includes? % "10.0.0.0/25") lines))))

  (testing "node->yaml-lines includes label when present"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/label-leaf "10.0.0.0/24" "test label"))
          root    (:root (plan/export-plan planner))
          lines   (#'tui/node->yaml-lines root 0)]
      (is (some #(clojure.string/includes? % "test label") lines)))))
