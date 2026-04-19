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
