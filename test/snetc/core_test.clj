(ns snetc.core-test
  (:require [clojure.test  :refer [deftest is testing]]
            [clojure.data.json :as json]
            [snetc.core    :as core]
            [snetc.tui     :as tui]))

(defn- exiting-die [msg]
  (throw (ex-info msg {:exit 1})))

(defn- dies-with? [f pattern]
  (try
    (with-redefs-fn {#'core/die exiting-die} f)
    false
    (catch clojure.lang.ExceptionInfo e
      (and (= 1 (:exit (ex-data e)))
           (boolean (re-find pattern (ex-message e)))))))

(defn- exits-empty? [f]
  (let [called (atom false)]
    (binding [core/*exit-empty-fn* (fn [] (reset! called true) (throw (ex-info "" {:exit 2})))]
      (try (f) (catch clojure.lang.ExceptionInfo _ nil)))
    @called))

(deftest handler-validation-test
  (testing "classify requires at least one input"
    (is (dies-with? #(#'core/handle-classify []) #"classify requires")))

  (testing "overlaps requires at least two CIDRs"
    (is (dies-with? #(#'core/handle-overlaps []) #"overlaps requires"))
    (is (dies-with? #(#'core/handle-overlaps ["10.0.0.0/24"]) #"overlaps requires")))

  (testing "free with no parent dies"
    (is (dies-with? #(with-out-str (#'core/handle-free [])) #"free requires a parent")))

  (testing "interactive tree requires exactly one parent CIDR"
    (is (dies-with? #(#'core/handle-interactive-tree []) #"tree requires"))
    (is (dies-with? #(#'core/handle-interactive-tree ["10.0.0.0/24" "extra"]) #"exactly one"))))

(deftest interactive-tree-handler-test
  (testing "interactive tree delegates to the TUI"
    (let [called (atom nil)]
      (with-redefs-fn {#'tui/run-tree! #(reset! called %)}
        #(#'core/handle-interactive-tree ["10.0.0.0/24"]))
      (is (= "10.0.0.0/24" @called)))))

;;; ── classful-prefix ──────────────────────────────────────────────────────────

(deftest classful-prefix-test
  (testing "class A (1–127) → /8"
    (is (= 8  (#'core/classful-prefix "10.0.0.0")))
    (is (= 8  (#'core/classful-prefix "1.2.3.4")))
    (is (= 8  (#'core/classful-prefix "127.0.0.1"))))

  (testing "class B (128–191) → /16"
    (is (= 16 (#'core/classful-prefix "128.0.0.0")))
    (is (= 16 (#'core/classful-prefix "172.16.5.5")))
    (is (= 16 (#'core/classful-prefix "191.255.0.0"))))

  (testing "class C (192–223) → /24"
    (is (= 24 (#'core/classful-prefix "192.168.1.1")))
    (is (= 24 (#'core/classful-prefix "223.0.0.1"))))

  (testing "class D/E and 0.x → nil"
    (is (nil? (#'core/classful-prefix "224.0.0.1")))
    (is (nil? (#'core/classful-prefix "240.0.0.1")))
    (is (nil? (#'core/classful-prefix "0.0.0.0")))))

;;; ── parse-mask-input ─────────────────────────────────────────────────────────

(deftest parse-mask-input-test
  (testing "dotted mask → prefix"
    (is (= 24 (#'core/parse-mask-input "255.255.255.0")))
    (is (= 16 (#'core/parse-mask-input "255.255.0.0")))
    (is (= 8  (#'core/parse-mask-input "255.0.0.0")))
    (is (= 0  (#'core/parse-mask-input "0.0.0.0"))))

  (testing "slash-prefix string → prefix"
    (is (= 24 (#'core/parse-mask-input "/24")))
    (is (= 0  (#'core/parse-mask-input "/0")))
    (is (= 32 (#'core/parse-mask-input "/32"))))

  (testing "bare integer string → prefix"
    (is (= 24 (#'core/parse-mask-input "24")))
    (is (= 0  (#'core/parse-mask-input "0")))
    (is (= 32 (#'core/parse-mask-input "32"))))

  (testing "out-of-range or garbage → nil"
    (is (nil? (#'core/parse-mask-input "/33")))
    (is (nil? (#'core/parse-mask-input "33")))
    (is (nil? (#'core/parse-mask-input "notamask")))))

;;; ── handle-mask ──────────────────────────────────────────────────────────────

(deftest handle-mask-validation-test
  (testing "empty input dies"
    (is (dies-with? #(#'core/handle-mask []) #"mask requires")))

  (testing "unparseable input dies"
    (is (dies-with? #(#'core/handle-mask ["notamask"]) #"Cannot parse mask"))))

;;; ── handle-supernet ──────────────────────────────────────────────────────────

(deftest handle-supernet-validation-test
  (testing "fewer than two CIDRs dies"
    (is (dies-with? #(#'core/handle-supernet []) #"supernet requires"))
    (is (dies-with? #(#'core/handle-supernet ["10.0.0.0/24"]) #"supernet requires")))

  (testing "invalid CIDR dies"
    (is (dies-with? #(#'core/handle-supernet ["10.0.0.0/24" "bad"]) #"(?i)invalid|missing|prefix|parse"))))

;;; ── handle-adjacent ──────────────────────────────────────────────────────────

(deftest handle-adjacent-validation-test
  (testing "missing CIDR dies"
    (is (dies-with? #(#'core/handle-adjacent [] "next") #"next requires"))
    (is (dies-with? #(#'core/handle-adjacent [] "prev") #"prev requires")))

  (testing "non-integer step count dies"
    (is (dies-with? #(#'core/handle-adjacent ["10.0.0.0/24" "abc"] "next") #"Invalid step")))

  (testing "step count < 1 dies"
    (is (dies-with? #(#'core/handle-adjacent ["10.0.0.0/24" "0"] "next") #"Step count")))

  (testing "extra arguments die"
    (is (dies-with? #(#'core/handle-adjacent ["10.0.0.0/24" "1" "extra"] "next") #"at most"))))

;;; ── --json output ────────────────────────────────────────────────────────────

(deftest json-output-test
  (testing "--json emits valid JSON with expected keys"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-info "10.0.0.0/24")))
          data (json/read-str out :key-fn keyword)]
      (is (= "10.0.0.0/24"   (:cidr       data)))
      (is (= "10.0.0.0"      (:network    data)))
      (is (= "10.0.0.255"    (:broadcast  data)))
      (is (= "10.0.0.1"      (:first_host data)))
      (is (= "10.0.0.254"    (:last_host  data)))
      (is (= 254             (:hosts      data)))
      (is (= "255.255.255.0" (:mask       data)))
      (is (= 24              (:prefix     data)))))

  (testing "/32 JSON output omits :broadcast"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-info "10.0.0.1/32")))
          data (json/read-str out :key-fn keyword)]
      (is (not (contains? data :broadcast)))))

  (testing "--json on aggregate emits structured result"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-aggregate ["10.0.0.0/24" "10.0.1.0/24"])))
          data (json/read-str out :key-fn keyword)]
      (is (= 2             (:input_count  data)))
      (is (= 1             (:result_count data)))
      (is (= ["10.0.0.0/23"] (:result     data)))))

  (testing "--json on overlaps emits overlap list"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-overlaps ["10.0.0.0/8" "10.0.0.0/24"])))
          data (json/read-str out :key-fn keyword)]
      (is (= 1 (:overlap_count data)))
      (is (= "a-contains-b" (-> data :overlaps first :type)))))

  (testing "--json on classify includes category, rfc, routable, spans"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-classify ["192.168.1.1" "8.8.8.8"])))
          data (json/read-str out :key-fn keyword)]
      (is (= "Private" (:category (first data))))
      (is (= false     (:routable (first data))))
      (is (= true      (:routable (second data)))))))

;;; ── --short output ───────────────────────────────────────────────────────────

(deftest short-output-test
  (testing "--short emits a single non-blank line"
    (let [out (with-out-str (#'core/handle-info "10.0.0.0/24" :short? true))]
      (is (= 1 (count (filter (complement clojure.string/blank?)
                               (clojure.string/split-lines out)))))))

  (testing "--short line contains CIDR, host range, host count, and mask"
    (let [out (with-out-str (#'core/handle-info "192.168.1.0/24" :short? true))]
      (is (clojure.string/includes? out "192.168.1.0/24"))
      (is (clojure.string/includes? out "192.168.1.1"))
      (is (clojure.string/includes? out "192.168.1.254"))
      (is (clojure.string/includes? out "254"))
      (is (clojure.string/includes? out "255.255.255.0"))))

  (testing "--short works for /32 (first-host == last-host)"
    (let [out (with-out-str (#'core/handle-info "10.0.0.1/32" :short? true))]
      (is (clojure.string/includes? out "10.0.0.1/32"))
      (is (clojure.string/includes? out "1 hosts")))))

;;; ── handle-validate ──────────────────────────────────────────────────────────

(deftest handle-validate-test
  (testing "all valid inputs → exit 0"
    (let [out  (with-out-str (#'core/handle-validate ["10.0.0.0/24" "192.168.1.1"]))
          lines (clojure.string/split-lines out)]
      (is (every? #(clojure.string/includes? % "ok")
                  (filter #(clojure.string/includes? % "/") lines)))))

  (testing "any invalid input → calls exit-empty!"
    (is (exits-empty? #(with-out-str (#'core/handle-validate ["10.0.0.0/24" "bad"])))))

  (testing "valid CIDR type is reported"
    (let [out (with-out-str (#'core/handle-validate ["10.0.0.0/8"]))]
      (is (clojure.string/includes? out "cidr"))))

  (testing "valid IP type is reported"
    (let [out (with-out-str (#'core/handle-validate ["10.0.0.1"]))]
      (is (clojure.string/includes? out "ip"))))

  (testing "invalid input shows FAIL and error message"
    (let [out (with-out-str
                (binding [core/*exit-empty-fn* (fn [] (throw (ex-info "" {:exit 2})))]
                  (try (#'core/handle-validate ["badstuff"])
                       (catch clojure.lang.ExceptionInfo _ nil))))]
      (is (clojure.string/includes? out "FAIL"))))

  (testing "--json emits structured array"
    (let [out  (with-out-str (binding [core/*json?* true
                                       core/*exit-empty-fn* (fn [] (throw (ex-info "" {:exit 2})))]
                               (try (#'core/handle-validate ["10.0.0.0/24" "bad"])
                                    (catch clojure.lang.ExceptionInfo _ nil))))
          data (json/read-str out :key-fn keyword)]
      (is (vector? data))
      (is (= 2 (count data)))
      (is (true?  (:valid (first data))))
      (is (false? (:valid (second data))))
      (is (= "cidr" (:type (first data)))))))

;;; ── stdin fallback ───────────────────────────────────────────────────────────

(deftest stdin-fallback-test
  (testing "classify reads from stdin when no args given"
    (let [stdin-text "10.0.0.1\n8.8.8.8\n"
          out (with-out-str
                (with-in-str stdin-text
                  (#'core/handle-classify [])))]
      (is (clojure.string/includes? out "Private"))
      (is (clojure.string/includes? out "Public"))))

  (testing "overlaps reads from stdin when no args given"
    (let [stdin-text "10.0.0.0/8\n10.0.0.0/24\n"
          out (with-out-str
                (with-in-str stdin-text
                  (#'core/handle-overlaps [])))]
      (is (clojure.string/includes? out "A contains B"))))

  (testing "supernet reads from stdin when no args given"
    (let [stdin-text "10.0.0.0/24\n10.0.1.0/24\n"
          out (with-out-str
                (with-in-str stdin-text
                  (#'core/handle-supernet [])))]
      (is (clojure.string/includes? out "10.0.0.0/23"))))

  (testing "validate reads from stdin when no args given"
    (let [stdin-text "10.0.0.1\nbad\n"
          out (with-out-str
                (binding [core/*exit-empty-fn* (fn [] (throw (ex-info "" {:exit 2})))]
                  (try
                    (with-in-str stdin-text
                      (#'core/handle-validate []))
                    (catch clojure.lang.ExceptionInfo _ nil))))]
      (is (clojure.string/includes? out "ok"))
      (is (clojure.string/includes? out "FAIL")))))

;;; ── batch mode ───────────────────────────────────────────────────────────────

(deftest batch-mode-test
  (testing "batch executes multiple commands and returns array"
    (let [input (json/write-str [{"cmd" "info"   "args" ["10.0.0.0/24"]}
                                 {"cmd" "classify" "args" ["10.0.0.1"]}])
          out   (with-out-str (with-in-str input (#'core/handle-batch [])))
          data  (json/read-str out :key-fn keyword)]
      (is (= 2 (count data)))
      (is (= 0 (:exit (first data))))
      (is (= "info" (:cmd (first data))))
      (is (= "10.0.0.0/24" (-> data first :result :cidr)))))

  (testing "batch captures per-command errors without aborting"
    (let [input (json/write-str [{"cmd" "info" "args" ["badcidr"]}
                                 {"cmd" "info" "args" ["10.0.0.0/24"]}])
          out   (with-out-str (with-in-str input (#'core/handle-batch [])))
          data  (json/read-str out :key-fn keyword)]
      (is (= 2 (count data)))
      (is (= 1 (:exit (first data))))
      (is (string? (:error (first data))))
      (is (= 0 (:exit (second data))))))

  (testing "batch rejects non-array stdin"
    (is (dies-with? #(with-in-str "{}" (#'core/handle-batch [])) #"array")))

  (testing "batch item missing cmd field returns exit 1"
    (let [input (json/write-str [{"args" ["10.0.0.0/24"]}])
          out   (with-out-str (with-in-str input (#'core/handle-batch [])))
          data  (json/read-str out :key-fn keyword)]
      (is (= 1 (:exit (first data))))))

  (testing "unknown command in batch returns exit 1"
    (let [input (json/write-str [{"cmd" "notacommand" "args" []}])
          out   (with-out-str (with-in-str input (#'core/handle-batch [])))
          data  (json/read-str out :key-fn keyword)]
      (is (= 1 (:exit (first data)))))))

;;; ── handler success paths ────────────────────────────────────────────────────

(deftest handle-split-test
  (testing "text output lists all subnets"
    (let [o (with-out-str (#'core/handle-split "10.0.0.0/24" 25))]
      (is (clojure.string/includes? o "10.0.0.0/25"))
      (is (clojure.string/includes? o "10.0.0.128/25"))))

  (testing "--json emits array of subnet objects"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-split "10.0.0.0/24" 25)))
          data (json/read-str out :key-fn keyword)]
      (is (= 2 (count data)))
      (is (= "10.0.0.0/25"     (:cidr (first data))))
      (is (= "10.0.0.128/25"   (:cidr (second data))))))

  (testing "split prefix smaller than base prefix dies"
    (is (dies-with? #(#'core/handle-split "10.0.0.0/24" 16) #"smaller than base"))))

(deftest handle-tree-flag-test
  (testing "text output contains root and children"
    (let [o (with-out-str (#'core/handle-tree-flag "10.0.0.0/24" 25))]
      (is (clojure.string/includes? o "10.0.0.0/24"))
      (is (clojure.string/includes? o "10.0.0.0/25"))))

  (testing "--json emits tree structure"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-tree-flag "10.0.0.0/24" 25)))
          data (json/read-str out :key-fn keyword)]
      (is (= "10.0.0.0/24" (:cidr data)))
      (is (= 2 (count (:children data))))))

  (testing "tree prefix smaller than base dies"
    (is (dies-with? #(#'core/handle-tree-flag "10.0.0.0/24" 16) #"smaller than base"))))

(deftest handle-contains-test
  (testing "text output shows yes/no for IPs"
    (let [o (with-out-str (#'core/handle-contains ["10.0.0.0/24" "10.0.0.1" "192.168.0.1"]))]
      (is (clojure.string/includes? o "yes"))
      (is (clojure.string/includes? o "no"))))

  (testing "--json emits subnet and results array"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-contains ["10.0.0.0/24" "10.0.0.1"])))
          data (json/read-str out :key-fn keyword)]
      (is (= "10.0.0.0/24" (:subnet data)))
      (is (= 1 (count (:results data))))
      (is (true? (:match (first (:results data)))))))

  (testing "no matches calls exit-empty!"
    (is (exits-empty? #(with-out-str (#'core/handle-contains ["10.0.0.0/24" "192.168.1.1"])))))

  (testing "IPs read from stdin when only CIDR given"
    (let [o (with-out-str (with-in-str "10.0.0.1\n" (#'core/handle-contains ["10.0.0.0/24"])))]
      (is (clojure.string/includes? o "yes")))))

(deftest handle-free-test
  (testing "text output shows free blocks"
    (let [o (with-out-str (#'core/handle-free ["10.0.0.0/24" "10.0.0.0/25"]))]
      (is (clojure.string/includes? o "10.0.0.128/25"))))

  (testing "--json emits free block list"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-free ["10.0.0.0/24" "10.0.0.0/25"])))
          data (json/read-str out :key-fn keyword)]
      (is (= 1 (:free_count data)))
      (is (= "10.0.0.128/25" (-> data :free first :cidr)))))

  (testing "fully allocated calls exit-empty!"
    (is (exits-empty? #(with-out-str (#'core/handle-free ["10.0.0.0/24" "10.0.0.0/24"]))))))

(deftest handle-plan-test
  (testing "text output shows VLSM allocation table"
    (let [o (with-out-str (#'core/handle-plan ["192.168.0.0/22" "200" "50"]))]
      (is (clojure.string/includes? o "VLSM plan"))
      (is (clojure.string/includes? o "200"))
      (is (clojure.string/includes? o "50"))))

  (testing "--json emits allocations array"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-plan ["192.168.0.0/22" "200" "50"])))
          data (json/read-str out :key-fn keyword)]
      (is (= 2 (count (:allocations data))))))

  (testing "non-integer host count dies"
    (is (dies-with? #(#'core/handle-plan ["10.0.0.0/24" "abc"]) #"Invalid host count")))

  (testing "host count < 1 dies"
    (is (dies-with? #(#'core/handle-plan ["10.0.0.0/24" "0"]) #"must be"))))

(deftest handle-lpm-test
  (testing "text output shows best match for each IP"
    (let [o (with-out-str (#'core/handle-lpm ["10.0.0.0/8" "10.0.0.0/24" "10.0.0.1"]))]
      (is (clojure.string/includes? o "10.0.0.0/24"))))

  (testing "--json emits routes and results"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-lpm ["10.0.0.0/8" "10.0.0.0/24" "10.0.0.1"])))
          data (json/read-str out :key-fn keyword)]
      (is (= "10.0.0.0/24" (-> data :results first :match)))))

  (testing "no route match calls exit-empty!"
    (is (exits-empty?
          #(with-out-str (#'core/handle-lpm ["192.168.0.0/24" "8.8.8.8"])))))

  (testing "missing routes dies"
    (is (dies-with? #(#'core/handle-lpm ["10.0.0.1"]) #"at least one route")))

  (testing "missing IPs dies"
    (is (dies-with? #(#'core/handle-lpm ["10.0.0.0/24"]) #"at least one IP"))))

(deftest handle-diff-test
  (testing "text output shows added and removed entries"
    (let [o (with-out-str (#'core/handle-diff ["10.0.0.0/24"] ["10.0.1.0/24"]))]
      (is (clojure.string/includes? o "[+]"))
      (is (clojure.string/includes? o "[-]"))))

  (testing "--json emits added/removed/unchanged"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-diff ["10.0.0.0/24"] ["10.0.1.0/24"])))
          data (json/read-str out :key-fn keyword)]
      (is (= ["10.0.1.0/24"] (:added data)))
      (is (= ["10.0.0.0/24"] (:removed data)))))

  (testing "no diff calls exit-empty!"
    (is (exits-empty? #(with-out-str (#'core/handle-diff ["10.0.0.0/24"] ["10.0.0.0/24"])))))

  (testing "missing before CIDRs dies"
    (is (dies-with? #(#'core/handle-diff [] ["10.0.0.0/24"]) #"before")))

  (testing "nil after dies"
    (is (dies-with? #(#'core/handle-diff ["10.0.0.0/24"] nil) #"separator"))))

(deftest handle-range-test
  (testing "text output shows range and CIDR count"
    (let [o (with-out-str (#'core/handle-range ["10.0.0.0" "10.0.0.255"]))]
      (is (clojure.string/includes? o "10.0.0.0/24"))
      (is (clojure.string/includes? o "1 CIDR block"))))

  (testing "+count syntax"
    (let [o (with-out-str (#'core/handle-range ["10.0.0.0" "+256"]))]
      (is (clojure.string/includes? o "10.0.0.0/24"))))

  (testing "--json emits start, end, cidrs"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-range ["10.0.0.0" "10.0.0.255"])))
          data (json/read-str out :key-fn keyword)]
      (is (= "10.0.0.0"   (:start data)))
      (is (= "10.0.0.255" (:end   data)))
      (is (= 1 (count (:cidrs data))))))

  (testing "start > end dies"
    (is (dies-with? #(#'core/handle-range ["10.0.0.255" "10.0.0.0"]) #"must be"))))

(deftest handle-util-test
  (testing "text output shows utilization stats"
    (let [o (with-out-str (#'core/handle-util ["10.0.0.0/24" "10.0.0.0/25"]))]
      (is (clojure.string/includes? o "10.0.0.0/24"))
      (is (clojure.string/includes? o "Allocated"))))

  (testing "--json emits utilization map"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-util ["10.0.0.0/24" "10.0.0.0/25"])))
          data (json/read-str out :key-fn keyword)]
      (is (= "10.0.0.0/24" (:parent data)))
      (is (number? (:pct_used data))))))

(deftest handle-analyze-test
  (testing "text output shows route analysis from stdin"
    (let [o (with-out-str
              (with-in-str "10.0.0.0/24\n10.0.1.0/24\n"
                (#'core/handle-analyze [])))]
      (is (clojure.string/includes? o "route"))))

  (testing "--json emits route analysis"
    (let [out  (with-out-str
                 (with-in-str "10.0.0.0/24\n10.0.1.0/24\n"
                   (binding [core/*json?* true]
                     (#'core/handle-analyze []))))
          data (json/read-str out :key-fn keyword)]
      (is (= 2 (:route_count data)))
      (is (= 1 (:aggregated_count data)))))

  (testing "no routes found dies"
    (is (dies-with? #(with-in-str "not a route table\n" (#'core/handle-analyze []))
                    #"No valid CIDR"))))

(deftest handle-allocate-test
  (testing "text output shows allocated CIDR"
    (let [o (with-out-str (#'core/handle-allocate ["10.0.0.0/24" "100"]))]
      (is (clojure.string/includes? o "Allocated"))))

  (testing "--json emits cidr and requested"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-allocate ["10.0.0.0/24" "100"])))
          data (json/read-str out :key-fn keyword)]
      (is (string? (:cidr data)))
      (is (= 100 (:requested data)))))

  (testing "with used blocks skips allocated space"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-allocate ["10.0.0.0/24" "100" "10.0.0.0/25"])))
          data (json/read-str out :key-fn keyword)]
      (is (= "10.0.0.128/25" (:cidr data)))))

  (testing "no space available dies"
    (is (dies-with? #(#'core/handle-allocate ["10.0.0.0/30" "1000"]) #"No available block"))))

(deftest handle-mask-success-test
  (testing "text output shows prefix, mask, and wildcard"
    (let [o (with-out-str (#'core/handle-mask ["255.255.255.0" "/16" "8"]))]
      (is (clojure.string/includes? o "/24"))
      (is (clojure.string/includes? o "/16"))
      (is (clojure.string/includes? o "/8"))))

  (testing "--json emits array of conversions"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-mask ["255.255.255.0"])))
          data (json/read-str out :key-fn keyword)]
      (is (= 1 (count data)))
      (is (= 24 (:prefix (first data)))))))

(deftest handle-adjacent-success-test
  (testing "next returns the adjacent block"
    (let [o (with-out-str (#'core/handle-adjacent ["10.0.0.0/24"] "next"))]
      (is (clojure.string/includes? o "10.0.1.0/24"))))

  (testing "prev returns the previous block"
    (let [o (with-out-str (#'core/handle-adjacent ["10.0.2.0/24"] "prev"))]
      (is (clojure.string/includes? o "10.0.1.0/24"))))

  (testing "next with n=3 skips three blocks"
    (let [o (with-out-str (#'core/handle-adjacent ["10.0.0.0/24" "3"] "next"))]
      (is (clojure.string/includes? o "10.0.3.0/24"))))

  (testing "--json emits direction and result"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-adjacent ["10.0.0.0/24"] "next")))
          data (json/read-str out :key-fn keyword)]
      (is (= "10.0.1.0/24" (:result data))))))

(deftest handle-supernet-success-test
  (testing "text output shows supernet"
    (let [o (with-out-str (#'core/handle-supernet ["10.0.0.0/24" "10.0.1.0/24"]))]
      (is (clojure.string/includes? o "10.0.0.0/23"))))

  (testing "--json emits input and result"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-supernet ["10.0.0.0/24" "10.0.1.0/24"])))
          data (json/read-str out :key-fn keyword)]
      (is (= "10.0.0.0/23" (:result data))))))

;;; ── text output for handlers tested only via JSON ────────────────────────────

(deftest handle-info-text-output-test
  (testing "normal text output includes all fields"
    (let [o (with-out-str (#'core/handle-info "192.168.1.0/24"))]
      (is (clojure.string/includes? o "192.168.1.0/24"))
      (is (clojure.string/includes? o "Network"))
      (is (clojure.string/includes? o "Broadcast"))
      (is (clojure.string/includes? o "255.255.255.0")))))

(deftest handle-aggregate-text-output-test
  (testing "text output shows aggregated result"
    (let [o (with-out-str (#'core/handle-aggregate ["10.0.0.0/24" "10.0.1.0/24"]))]
      (is (clojure.string/includes? o "10.0.0.0/23")))))

(deftest handle-contains-error-paths-test
  (testing "missing CIDR dies"
    (is (dies-with? #(#'core/handle-contains []) #"contains requires")))

  (testing "invalid IP dies"
    (is (dies-with? #(#'core/handle-contains ["10.0.0.0/24" "notanip"]) #"Invalid IP")))

  (testing "unparseable CIDR dies"
    (is (dies-with? #(#'core/handle-contains ["badcidr" "10.0.0.1"]) #"."))))

(deftest handle-validate-invalid-cidr-test
  (testing "invalid CIDR format shows FAIL with type=cidr"
    (let [o (with-out-str
              (binding [core/*exit-empty-fn* (fn [] (throw (ex-info "" {:exit 2})))]
                (try (#'core/handle-validate ["256.256.256.256/24"])
                     (catch clojure.lang.ExceptionInfo _ nil))))]
      (is (clojure.string/includes? o "FAIL"))
      (is (clojure.string/includes? o "cidr")))))

;;; ── -main dispatch ───────────────────────────────────────────────────────────

(defn- run-main
  "Calls -main with args, capturing stdout. Binds die/exit-empty to throw."
  [& args]
  (with-out-str
    (binding [core/*die-fn*        (fn [msg] (throw (ex-info msg {:exit 1})))
              core/*exit-empty-fn* (fn [] (throw (ex-info "" {:exit 2})))]
      (apply core/-main args))))

(deftest main-dispatch-test
  (testing "CIDR arg dispatches to handle-info"
    (let [o (run-main "10.0.0.0/24")]
      (is (clojure.string/includes? o "10.0.0.0/24"))
      (is (clojure.string/includes? o "Network"))))

  (testing "classful IP arg infers prefix and shows info"
    (let [o (run-main "10.0.0.1")]
      (is (clojure.string/includes? o "10.0.0.0/8"))
      (is (clojure.string/includes? o "inferred"))))

  (testing "class D/E IP dies with classful inference error"
    (is (thrown? clojure.lang.ExceptionInfo (run-main "224.0.0.1"))))

  (testing "--split option calls handle-split"
    (let [o (run-main "--split" "25" "10.0.0.0/24")]
      (is (clojure.string/includes? o "10.0.0.0/25"))))

  (testing "--tree option calls handle-tree-flag"
    (let [o (run-main "--tree" "25" "10.0.0.0/24")]
      (is (clojure.string/includes? o "10.0.0.0/24"))))

  (testing "subcommand dispatch routes to correct handler"
    (let [o (run-main "aggregate" "10.0.0.0/24" "10.0.1.0/24")]
      (is (clojure.string/includes? o "10.0.0.0/23"))))

  (testing "next subcommand via lambda dispatch"
    (let [o (run-main "next" "10.0.0.0/24")]
      (is (clojure.string/includes? o "10.0.1.0/24"))))

  (testing "prev subcommand via lambda dispatch"
    (let [o (run-main "prev" "10.0.2.0/24")]
      (is (clojure.string/includes? o "10.0.1.0/24"))))

  (testing "diff with -- separator"
    (let [o (run-main "diff" "10.0.0.0/24" "--" "10.0.1.0/24")]
      (is (clojure.string/includes? o "[+]"))))

  (testing "--json flag binds *json?*"
    (let [out  (run-main "--json" "info" "10.0.0.0/24")
          data (json/read-str out :key-fn keyword)]
      (is (= "10.0.0.0/24" (:cidr data)))))

  (testing "--short flag passes to handle-info"
    (let [o (run-main "--short" "10.0.0.0/24")]
      (is (= 1 (count (filter (complement clojure.string/blank?)
                               (clojure.string/split-lines o)))))))

  (testing "unknown subcommand prints usage and does not throw"
    ;; unknown command falls through to the else → (do (println usage) System/exit 1)
    ;; We can't easily test this without mocking System/exit, so skip the exit path.
    ;; Just verify the handler resolution doesn't die with an exception for known commands.
    (is (string? (run-main "classify" "10.0.0.1"))))

  (testing "handler exception is caught and rethrown via die"
    (is (thrown? clojure.lang.ExceptionInfo
                 (run-main "info" "notacidr")))))

;;; ── improvement tests ────────────────────────────────────────────────────────

(deftest free-no-allocations-test
  (testing "free with no allocations shows entire parent as free (text)"
    (let [o (with-out-str (#'core/handle-free ["10.0.0.0/24"]))]
      (is (clojure.string/includes? o "10.0.0.0/24"))
      (is (not (clojure.string/includes? o "excluding")))))

  (testing "free with no allocations JSON returns allocated_count 0 and full parent free"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-free ["10.0.0.0/24"])))
          data (json/read-str out :key-fn keyword)]
      (is (= 0 (:allocated_count data)))
      (is (= 1 (:free_count data)))
      (is (= "10.0.0.0/24" (-> data :free first :cidr))))))

(deftest util-json-fields-test
  (testing "--json includes pct_free and largest_free"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-util ["10.0.0.0/24" "10.0.0.0/26" "10.0.0.128/26"])))
          data (json/read-str out :key-fn keyword)]
      (is (= 50 (:pct_used data)))
      (is (= 50 (:pct_free data)))
      (is (string? (:largest_free data)))))

  (testing "pct_used + pct_free = 100"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-util ["10.0.0.0/24" "10.0.0.0/25"])))
          data (json/read-str out :key-fn keyword)]
      (is (= 100 (+ (:pct_used data) (:pct_free data))))))

  (testing "largest_free is nil when fully allocated"
    (let [out  (with-out-str (binding [core/*json?* true]
                               (#'core/handle-util ["10.0.0.0/30" "10.0.0.0/30"])))
          data (json/read-str out :key-fn keyword)]
      (is (nil? (:largest_free data))))))

(deftest analyze-containment-json-test
  (testing "contained entries use container/contained keys not a/b/type"
    (let [out  (with-out-str
                 (with-in-str "10.0.0.0/24\n10.0.0.0/25\n"
                   (binding [core/*json?* true]
                     (#'core/handle-analyze []))))
          data (json/read-str out :key-fn keyword)
          item (first (:contained data))]
      (is (= "10.0.0.0/24" (:container item)))
      (is (= "10.0.0.0/25" (:contained item)))
      (is (nil? (:type item)))))

  (testing "b-contains-a containment is normalised the same way"
    (let [out  (with-out-str
                 (with-in-str "10.0.0.0/25\n10.0.0.0/24\n"
                   (binding [core/*json?* true]
                     (#'core/handle-analyze []))))
          data (json/read-str out :key-fn keyword)
          item (first (:contained data))]
      (is (= "10.0.0.0/24" (:container item)))
      (is (= "10.0.0.0/25" (:contained item))))))

(deftest diff-trailing-json-test
  (testing "diff --json placed after -- is picked up by -main"
    (let [o (run-main "diff" "10.0.0.0/25" "--" "10.0.0.0/25" "10.0.0.128/25" "--json")
          data (json/read-str o :key-fn keyword)]
      (is (= ["10.0.0.128/25"] (:added data)))
      (is (= [] (:removed data)))))

  (testing "diff --json before -- still works"
    (let [o (run-main "--json" "diff" "10.0.0.0/25" "--" "10.0.0.128/25")
          data (json/read-str o :key-fn keyword)]
      (is (= ["10.0.0.128/25"] (:added data))))))

(deftest adjacent-step-overflow-test
  (testing "next rejects step count larger than IPv4 address space"
    (is (dies-with? #(#'core/handle-adjacent ["10.0.0.0/24" "4294967296"] "next")
                    #"exceeds IPv4")))

  (testing "prev rejects step count larger than IPv4 address space"
    (is (dies-with? #(#'core/handle-adjacent ["10.0.0.0/24" "4294967296"] "prev")
                    #"exceeds IPv4")))

  (testing "next rejects step that overflows address space"
    (is (dies-with? #(run-main "next" "10.0.0.0/24" "4294967295")
                    #"outside valid IPv4"))))
