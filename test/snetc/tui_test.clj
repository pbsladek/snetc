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
    (is (= 0 (:selected (press base-state :up)))))

  (testing "g/G jump to first and last visible rows"
    (let [state (press base-state :split)]
      (is (= 1 (:selected (press state :last))))
      (is (= 0 (:selected (press (press state :last) :first))))))

  (testing "page up/down moves by viewport height when available"
    (let [state (assoc (press base-state :split)
                       :last-frame {:lines (vec (repeat 9 ""))})
          at-end (press state :page-down)]
      (is (= 1 (:selected at-end)))
      (is (= 0 (:selected (press at-end :page-up)))))))

(deftest cached-row-state-test
  (testing "plan-changing keys refresh cached rows"
    (let [after (press base-state :split)]
      (is (= 2 (count (:rows after))))
      (is (= ["10.0.0.0/25" "10.0.0.128/25"]
             (mapv :cidr (:rows after))))))

  (testing "cached rows are used for selection"
    (let [state {:plan (plan/new-plan "10.0.0.0/24")
                 :rows [{:idx 1 :cidr "10.0.0.0/25"}
                        {:idx 2 :cidr "10.0.0.128/25"}]
                 :selected 0
                 :scroll 0
                 :message "Ready"}
          after (press state :down)]
      (is (= 1 (:selected after)))
      (is (= "10.0.0.128/25" (:cursor (:plan after)))))))

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

(deftest terminal-adapter-test
  (testing "label prompt can be injected without touching /dev/tty"
    (let [state (assoc base-state
                       :terminal {:prompt (fn [_saved-mode _prompt] "edge")})
          result (press state :label)]
      (is (= "edge" (:label (plan/find-node (:plan result) "10.0.0.0/24"))))
      (is (= "edge" (:label (first (:rows result)))))))

  (testing "print-cidrs writer can be injected"
    (let [called (atom nil)
          state (assoc base-state
                       :terminal {:write-leaf-cidrs (fn [planner]
                                                      (reset! called (plan/leaf-cidrs planner))
                                                      "memory://leaves")})
          result (press state :print-cidrs)]
      (is (= ["10.0.0.0/24"] @called))
      (is (clojure.string/includes? (:message result) "memory://leaves")))))

(deftest search-filter-and-command-test
  (testing "filter narrows selectable rows"
    (let [state (-> base-state
                    (press :split)
                    (assoc :terminal {:prompt (fn [_ _] "10.0.0.128")}))
          result (press state :filter)]
      (is (= "10.0.0.128/25" (:cursor (:plan result))))
      (is (clojure.string/includes? (:message result) "1 row"))))

  (testing "search selects by IP containment"
    (let [state (-> base-state
                    (press :split)
                    (assoc :terminal {:prompt (fn [_ _] "10.0.0.200")}))
          result (press state :jump)]
      (is (= "10.0.0.128/25" (:cursor (:plan result))))))

  (testing "command palette can split by prefix and clear filters"
    (let [prompts (atom ["split /26" "filter 10.0.0.64" "clear"])
          state (assoc base-state :terminal {:prompt (fn [_ _]
                                                       (let [v (first @prompts)]
                                                         (swap! prompts rest)
                                                         v))})
          split-state (press state :command)
          filtered-state (press split-state :command)
          cleared-state (press filtered-state :command)]
      (is (= 4 (count (plan/leaf-cidrs (:plan split-state)))))
      (is (= "10.0.0.64/26" (:cursor (:plan filtered-state))))
      (is (nil? (:filter cleared-state)))))

  (testing "escape clears an active filter"
    (let [state (-> base-state
                    (press :split)
                    (assoc :terminal {:prompt (fn [_ _] "10.0.0.128")})
                    (press :filter))
          cleared (press state :escape)]
      (is (nil? (:filter cleared)))
      (is (clojure.string/includes? (:message cleared) "cleared"))))

  (testing "query aliases match prefix and label"
    (let [state (-> base-state
                    (press :split)
                    (assoc :terminal {:prompt (fn [_ _] "edge")}))
          labeled (assoc (press state :label)
                         :terminal {:prompt (fn [_ _] "@edge")})
          by-label (press labeled :filter)
          by-prefix (press (assoc labeled :terminal {:prompt (fn [_ _] "/25")}) :filter)]
      (is (= "10.0.0.0/25" (:cursor (:plan by-label))))
      (is (clojure.string/includes? (:message by-prefix) "2 row"))))

  (testing "command aliases and help work"
    (let [prompts (atom ["s /26" "h 62" "f /26" "x" "?"])
          state (assoc base-state :terminal {:prompt (fn [_ _]
                                                       (let [v (first @prompts)]
                                                         (swap! prompts rest)
                                                         v))})
          split-state (press state :command)
          hosts-state (press split-state :command)
          filtered-state (press hosts-state :command)
          cleared-state (press filtered-state :command)
          help-state (press cleared-state :command)]
      (is (= 4 (count (plan/leaf-cidrs (:plan split-state)))))
      (is (string? (:message hosts-state)))
      (is (some? (:filter filtered-state)))
      (is (nil? (:filter cleared-state)))
      (is (clojure.string/includes? (:message help-state) "Commands:")))))

(deftest bulk-operation-key-test
  (testing "split-to-prefix key recursively splits selected subnet"
    (let [state (assoc base-state :terminal {:prompt (fn [_ _] "/26")})
          result (press state :split-to-prefix)]
      (is (= ["10.0.0.0/26" "10.0.0.64/26" "10.0.0.128/26" "10.0.0.192/26"]
             (plan/leaf-cidrs (:plan result))))))

  (testing "split-hosts key chooses a tight fitting prefix"
    (let [state (assoc base-state :terminal {:prompt (fn [_ _] "62")})
          result (press state :split-hosts)]
      (is (= ["10.0.0.0/26" "10.0.0.64/26" "10.0.0.128/26" "10.0.0.192/26"]
             (plan/leaf-cidrs (:plan result)))))))

(deftest import-key-test
  (testing "import reads a plan through the terminal adapter"
    (let [imported (-> (plan/new-plan "10.0.0.0/24")
                       (plan/split-leaf "10.0.0.0/24"))
          state (assoc base-state
                       :terminal {:prompt (fn [_ _] "memory://plan.edn")
                                  :read-plan (fn [_] imported)})
          result (press state :import)]
      (is (= ["10.0.0.0/25" "10.0.0.128/25"]
             (plan/leaf-cidrs (:plan result))))
      (is (clojure.string/includes? (:message result) "Imported"))))

  (testing "import errors distinguish missing files"
    (let [result (#'tui/import-plan-path base-state "/tmp/snetc-missing-plan.edn")]
      (is (clojure.string/includes? (:message result) "not found")))))

(deftest export-and-selected-output-test
  (testing "command export accepts an explicit path"
    (let [called (atom nil)
          state (assoc base-state
                       :terminal {:prompt (fn [_ _] "export json /tmp/custom.json")
                                  :write-json-plan-to (fn [_ path]
                                                        (reset! called path)
                                                        path)})
          result (press state :command)]
      (is (= "/tmp/custom.json" @called))
      (is (clojure.string/includes? (:message result) "/tmp/custom.json"))))

  (testing "print-selected writes selected CIDR through the adapter"
    (let [called (atom nil)
          state (assoc base-state
                       :terminal {:prompt (fn [_ _] "print-selected")
                                  :write-selected-cidr (fn [row]
                                                         (reset! called (:cidr row))
                                                         "memory://selected")})
          result (press state :command)]
      (is (= "10.0.0.0/24" @called))
      (is (clojure.string/includes? (:message result) "memory://selected")))))

(deftest bulk-confirmation-test
  (testing "large bulk split can be cancelled before expansion"
    (let [prompts (atom ["/32" "no"])
          state (assoc {:plan (plan/new-plan "10.0.0.0/16")
                        :selected 0
                        :scroll 0
                        :message "Ready"}
                       :terminal {:prompt (fn [_ _]
                                            (let [v (first @prompts)]
                                              (swap! prompts rest)
                                              v))})
          result (press state :split-to-prefix)]
      (is (= ["10.0.0.0/16"] (plan/leaf-cidrs (:plan result))))
      (is (= "Split cancelled" (:message result))))))

(deftest injected-run-loop-test
  (testing "run-tree! can be driven with an injected terminal adapter"
    (when-not (= "dumb" (System/getenv "TERM"))
      (let [events (atom [])
            final-plan (with-out-str
                         (let [result (tui/run-tree!
                                      "10.0.0.0/24"
                                      {:terminal-mode (fn [] "saved")
                                       :set-terminal-mode! #(swap! events conj [:restore %])
                                       :raw-mode! #(swap! events conj :raw)
                                       :enter-screen! #(swap! events conj :enter)
                                       :leave-screen! #(swap! events conj :leave)
                                       :terminal-size (fn [] [80 12])
                                       :open-input (fn [] (java.io.ByteArrayInputStream. (byte-array 0)))
                                       :read-key (fn [_in] :quit)})]
                           (is (= ["10.0.0.0/24"] (plan/leaf-cidrs result)))))]
        (is (string? final-plan))
        (is (= [:enter :raw [:restore "saved"] :leave] @events)))))

  (testing "second loop render uses diff output instead of full clear"
    (when-not (= "dumb" (System/getenv "TERM"))
      (let [keys (atom [:timeout :quit])
            writes (atom [])]
        (tui/run-tree!
         "10.0.0.0/24"
         {:terminal-mode (fn [] "saved")
          :set-terminal-mode! (fn [_])
          :raw-mode! (fn [])
          :enter-screen! (fn [])
          :leave-screen! (fn [])
          :terminal-size (fn [] [80 12])
          :open-input (fn [] (java.io.ByteArrayInputStream. (byte-array 0)))
          :read-key (fn [_in]
                      (let [k (first @keys)]
                        (swap! keys rest)
                        k))
          :write! #(swap! writes conj %)})
        (is (= 2 (count @writes)))
        (is (clojure.string/includes? (first @writes) "\u001b[2J"))
        (is (not (clojure.string/includes? (second @writes) "\u001b[2J")))))))

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

(deftest read-key-test
  (testing "escape sequences map page keys and bare escape"
    (is (= :escape (#'tui/read-key (java.io.ByteArrayInputStream. (byte-array [27])))))
    (is (= :page-up (#'tui/read-key (java.io.ByteArrayInputStream. (byte-array [27 91 53 126])))))
    (is (= :page-down (#'tui/read-key (java.io.ByteArrayInputStream. (byte-array [27 91 54 126]))))))

  (testing "letter keys map first/last navigation"
    (is (= :first (#'tui/read-key (java.io.ByteArrayInputStream. (byte-array [(byte 103)])))))
    (is (= :last (#'tui/read-key (java.io.ByteArrayInputStream. (byte-array [(byte 71)])))))))

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
      (is (some #(clojure.string/includes? % "test label") lines))))

  (testing "node->yaml-lines escapes backslashes in labels"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/label-leaf "10.0.0.0/24" "C:\\network"))
          root    (:root (plan/export-plan planner))
          lines   (#'tui/node->yaml-lines root 0)
          label-line (first (filter #(clojure.string/includes? % "label:") lines))]
      (is (clojure.string/includes? label-line "C:\\\\network"))))

  (testing "node->yaml-lines escapes double-quotes in labels"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/label-leaf "10.0.0.0/24" "say \"hello\""))
          root    (:root (plan/export-plan planner))
          lines   (#'tui/node->yaml-lines root 0)
          label-line (first (filter #(clojure.string/includes? % "label:") lines))]
      (is (clojure.string/includes? label-line "say \\\"hello\\\"")))))
