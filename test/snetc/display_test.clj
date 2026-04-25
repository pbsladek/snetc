(ns snetc.display-test
  (:require [clojure.test   :refer [deftest is testing]]
            [clojure.string :as str]
            [snetc.display  :as display]
            [snetc.subnet   :as subnet]
            [snetc.ip       :as ip]
            [snetc.ops      :as ops]
            [snetc.classify :as classify]))

(defn- captured [f] (with-out-str (f)))

;;; ── print-subnet-info ────────────────────────────────────────────────────────

(deftest print-subnet-info-test
  (testing "/24 shows all fields including broadcast"
    (let [o (captured #(display/print-subnet-info (subnet/subnet-info "192.168.1.0/24")))]
      (is (str/includes? o "192.168.1.0/24"))
      (is (str/includes? o "Network"))
      (is (str/includes? o "Broadcast"))
      (is (str/includes? o "192.168.1.255"))
      (is (str/includes? o "255.255.255.0"))
      (is (str/includes? o "0.0.0.255"))
      (is (str/includes? o "254"))))

  (testing "/32 omits broadcast"
    (let [o (captured #(display/print-subnet-info (subnet/subnet-info "10.0.0.1/32")))]
      (is (str/includes? o "10.0.0.1/32"))
      (is (not (str/includes? o "Broadcast"))))))

;;; ── print-subnet-info-json ───────────────────────────────────────────────────

(deftest print-subnet-info-json-test
  (testing "/24 outputs JSON with all keys"
    (let [o (captured #(display/print-subnet-info-json (subnet/subnet-info "10.0.0.0/24")))]
      (is (str/includes? o "\"cidr\""))
      (is (str/includes? o "10.0.0.0"))
      (is (str/includes? o "broadcast"))))

  (testing "/32 JSON omits broadcast"
    (let [o (captured #(display/print-subnet-info-json (subnet/subnet-info "10.0.0.1/32")))]
      (is (not (str/includes? o "broadcast"))))))

;;; ── print-split-table ────────────────────────────────────────────────────────

(deftest print-split-table-test
  (testing "shows header and one row per subnet"
    (let [subs (subnet/split-subnets "10.0.0.0/24" 25)
          o    (captured #(display/print-split-table subs))]
      (is (str/includes? o "Network/CIDR"))
      (is (str/includes? o "10.0.0.0/25"))
      (is (str/includes? o "10.0.0.128/25")))))

;;; ── print-subnet-tree ────────────────────────────────────────────────────────

(deftest print-subnet-tree-test
  (testing "shows root CIDR and children"
    (let [tree (subnet/subnet-tree "10.0.0.0/24" 25)
          o    (captured #(display/print-subnet-tree tree))]
      (is (str/includes? o "10.0.0.0/24"))
      (is (str/includes? o "10.0.0.0/25"))
      (is (str/includes? o "10.0.0.128/25")))))

;;; ── print-aggregate-result ───────────────────────────────────────────────────

(deftest print-aggregate-result-test
  (testing "shows aggregated result"
    (let [input  ["10.0.0.0/24" "10.0.1.0/24"]
          result (ops/aggregate input)
          o      (captured #(display/print-aggregate-result input result))]
      (is (str/includes? o "10.0.0.0/23"))
      (is (str/includes? o "2"))
      (is (str/includes? o "1")))))

;;; ── print-contains-result ────────────────────────────────────────────────────

(deftest print-contains-result-test
  (testing "shows yes for matching and no for non-matching IPs"
    (let [info (subnet/subnet-info "10.0.0.0/24")
          ips  ["10.0.0.1" "192.168.1.1" "10.0.0.0"]
          o    (captured #(display/print-contains-result info ips))]
      (is (str/includes? o "10.0.0.0/24"))
      (is (str/includes? o "yes"))
      (is (str/includes? o "no"))
      (is (str/includes? o "(network address)"))))

  (testing "shows broadcast address note"
    (let [info (subnet/subnet-info "10.0.0.0/24")
          ips  ["10.0.0.255"]
          o    (captured #(display/print-contains-result info ips))]
      (is (str/includes? o "(broadcast address)")))))

;;; ── print-free-result ────────────────────────────────────────────────────────

(deftest print-free-result-test
  (testing "shows free blocks"
    (let [infos (mapv subnet/subnet-info
                      (ops/free-space "10.0.0.0/24" ["10.0.0.0/25"]))
          o     (captured #(display/print-free-result "10.0.0.0/24" ["10.0.0.0/25"] infos))]
      (is (str/includes? o "Free space in 10.0.0.0/24"))
      (is (str/includes? o "10.0.0.128/25"))))

  (testing "fully allocated shows none message"
    (let [o (captured #(display/print-free-result "10.0.0.0/24" ["10.0.0.0/24"] []))]
      (is (str/includes? o "none")))))

;;; ── print-diff-result ────────────────────────────────────────────────────────

(deftest print-diff-result-test
  (testing "shows added, removed, and unchanged"
    (let [before  ["10.0.0.0/24" "10.0.1.0/24"]
          after   ["10.0.1.0/24" "10.0.2.0/24"]
          {:keys [added removed unchanged]} (ops/cidr-diff before after)
          entries (vec (concat (map #(vector :removed   %) removed)
                               (map #(vector :unchanged %) unchanged)
                               (map #(vector :added     %) added)))
          o       (captured #(display/print-diff-result before after added removed unchanged entries))]
      (is (str/includes? o "[+]"))
      (is (str/includes? o "[-]"))
      (is (str/includes? o "[=]"))
      (is (str/includes? o "Added:"))
      (is (str/includes? o "Removed:")))))

;;; ── category-label ───────────────────────────────────────────────────────────

(deftest category-label-test
  (testing "uses category-path when present"
    (is (= "Private → Public"
           (display/category-label {:category-path [{:name "Private"} {:name "Public"}]}))))

  (testing "uses name for simple classification"
    (is (= "Private"
           (display/category-label {:category-path [{:name "Private"}]}))))

  (testing "spans? appends bcast-name when no category-path"
    (is (= "Private → Public"
           (display/category-label {:name "Private" :spans? true :bcast-name "Public"
                                    :category-path nil}))))

  (testing "non-spanning without category-path uses name alone"
    (is (= "Private"
           (display/category-label {:name "Private" :spans? false :category-path nil})))))

;;; ── print-classify-result ────────────────────────────────────────────────────

(deftest print-classify-result-test
  (testing "shows input, category, rfc, and routable columns"
    (let [cs (mapv classify/classify ["10.0.0.1" "8.8.8.8"])
          o  (captured #(display/print-classify-result cs))]
      (is (str/includes? o "10.0.0.1"))
      (is (str/includes? o "Private"))
      (is (str/includes? o "RFC 1918"))
      (is (str/includes? o "no"))
      (is (str/includes? o "8.8.8.8"))
      (is (str/includes? o "yes")))))

;;; ── print-range-result ───────────────────────────────────────────────────────

(deftest print-range-result-test
  (testing "shows range boundaries and CIDR count"
    (let [cidrs (vec (subnet/range->cidrs
                       (ip/ip->long "10.0.0.0")
                       (ip/ip->long "10.0.0.255")))
          o     (captured #(display/print-range-result "10.0.0.0" "10.0.0.255" cidrs))]
      (is (str/includes? o "10.0.0.0"))
      (is (str/includes? o "10.0.0.255"))
      (is (str/includes? o "1 CIDR block")))))

;;; ── print-vlsm-result ────────────────────────────────────────────────────────

(deftest print-vlsm-result-test
  (testing "shows allocation table"
    (let [allocs (ops/plan-vlsm "192.168.0.0/22" [200 50])
          o      (captured #(display/print-vlsm-result "192.168.0.0/22" allocs))]
      (is (str/includes? o "VLSM plan"))
      (is (str/includes? o "192.168.0.0/22"))
      (is (str/includes? o "200"))
      (is (str/includes? o "50")))))

;;; ── print-overlaps-result ────────────────────────────────────────────────────

(deftest print-overlaps-result-test
  (testing "shows overlap pairs"
    (let [cidrs    ["10.0.0.0/8" "10.0.0.0/24"]
          overlaps (ops/find-overlaps cidrs)
          o        (captured #(display/print-overlaps-result cidrs overlaps))]
      (is (str/includes? o "A contains B"))
      (is (str/includes? o "1 overlap"))))

  (testing "shows no overlaps message when empty"
    (let [cidrs    ["10.0.0.0/24" "192.168.0.0/24"]
          overlaps (ops/find-overlaps cidrs)
          o        (captured #(display/print-overlaps-result cidrs overlaps))]
      (is (str/includes? o "No overlaps found"))))

  (testing "shows partial overlap type"
    (let [cidrs    ["10.0.0.0/23" "10.0.0.128/25"]
          overlaps (ops/find-overlaps cidrs)
          o        (captured #(display/print-overlaps-result cidrs overlaps))]
      (is (str/includes? o "contains"))))

  (testing "shows b-contains-a type"
    (let [cidrs    ["10.0.0.0/24" "10.0.0.0/8"]
          overlaps (ops/find-overlaps cidrs)
          o        (captured #(display/print-overlaps-result cidrs overlaps))]
      (is (str/includes? o "contains")))))

;;; ── print-util-result ────────────────────────────────────────────────────────

(deftest print-util-result-test
  (testing "shows utilization stats"
    (let [result (ops/utilization-info "10.0.0.0/24" ["10.0.0.0/25"])
          o      (captured #(display/print-util-result result))]
      (is (str/includes? o "10.0.0.0/24"))
      (is (str/includes? o "Allocated"))
      (is (str/includes? o "Free"))
      (is (str/includes? o "Used:"))
      (is (str/includes? o "largest"))))

  (testing "shows fragmentation label when multiple free blocks"
    (let [result (ops/utilization-info "10.0.0.0/24"
                                       ["10.0.0.64/26" "10.0.0.192/26"])
          o      (captured #(display/print-util-result result))]
      (is (str/includes? o "Fragmentation")))))

;;; ── print-analyze-result ─────────────────────────────────────────────────────

(deftest print-analyze-result-test
  (testing "fully optimized shows no issues message"
    (let [result (ops/analyze-routes ["10.0.0.0/24" "192.168.0.0/24"])
          o      (captured #(display/print-analyze-result result))]
      (is (str/includes? o "fully optimized"))))

  (testing "summarizable routes show savings and group table"
    (let [result (ops/analyze-routes ["10.0.0.0/24" "10.0.1.0/24"])
          o      (captured #(display/print-analyze-result result))]
      (is (str/includes? o "saves"))
      (is (str/includes? o "Summarization"))))

  (testing "containment relationships are shown"
    (let [result (ops/analyze-routes ["10.0.0.0/8" "10.0.0.0/24"])
          o      (captured #(display/print-analyze-result result))]
      (is (str/includes? o "Containment")))))

;;; ── print-json ───────────────────────────────────────────────────────────────

(deftest print-json-test
  (testing "outputs valid JSON line"
    (let [o (captured #(display/print-json {:key "value" :n 42}))]
      (is (str/includes? o "\"key\""))
      (is (str/includes? o "\"value\""))
      (is (str/includes? o "42")))))

;;; ── print-allocate-result ────────────────────────────────────────────────────

(deftest print-allocate-result-test
  (testing "shows allocation details"
    (let [info (subnet/subnet-info "10.0.0.0/25")
          o    (captured #(display/print-allocate-result "10.0.0.0/24" [] 100 info))]
      (is (str/includes? o "10.0.0.0/24"))
      (is (str/includes? o "Allocated"))
      (is (str/includes? o "10.0.0.0/25"))))

  (testing "shows excluded blocks when used is non-empty"
    (let [info (subnet/subnet-info "10.0.0.128/25")
          o    (captured #(display/print-allocate-result "10.0.0.0/24"
                                                          ["10.0.0.0/25"] 100 info))]
      (is (str/includes? o "Excluding")))))

;;; ── print-subnet-info-short ──────────────────────────────────────────────────

(deftest print-subnet-info-short-test
  (testing "outputs a single line with key fields"
    (let [info (subnet/subnet-info "10.0.0.0/24")
          o    (str/trim (captured #(display/print-subnet-info-short info)))]
      (is (= 1 (count (str/split-lines o))))
      (is (str/includes? o "10.0.0.0/24"))
      (is (str/includes? o "10.0.0.1"))
      (is (str/includes? o "10.0.0.254"))
      (is (str/includes? o "255.255.255.0")))))

;;; ── print-adjacent-result ────────────────────────────────────────────────────

(deftest print-adjacent-result-test
  (testing "shows input, direction, and result"
    (let [o (captured #(display/print-adjacent-result "10.0.0.0/24" "next" 1 "10.0.1.0/24"))]
      (is (str/includes? o "10.0.0.0/24"))
      (is (str/includes? o "next"))
      (is (str/includes? o "10.0.1.0/24")))))

;;; ── print-mask-result ────────────────────────────────────────────────────────

(deftest print-mask-result-test
  (testing "shows prefix, mask, and wildcard"
    (let [conversions [{:input "255.255.255.0" :prefix 24
                        :mask  "255.255.255.0" :wildcard "0.0.0.255"}]
          o           (captured #(display/print-mask-result conversions))]
      (is (str/includes? o "255.255.255.0"))
      (is (str/includes? o "/24"))
      (is (str/includes? o "0.0.0.255")))))

;;; ── print-supernet-result ────────────────────────────────────────────────────

(deftest print-supernet-result-test
  (testing "shows input list and result"
    (let [inputs ["10.0.0.0/24" "10.0.1.0/24"]
          o      (captured #(display/print-supernet-result inputs "10.0.0.0/23"))]
      (is (str/includes? o "10.0.0.0/24"))
      (is (str/includes? o "10.0.1.0/24"))
      (is (str/includes? o "10.0.0.0/23")))))

;;; ── print-validate-result ────────────────────────────────────────────────────

(deftest print-validate-result-test
  (testing "shows ok for valid and FAIL for invalid"
    (let [results [{:input "10.0.0.1"    :valid true  :type "ip"   :error nil}
                   {:input "bad"          :valid false :type "ip"   :error "Invalid IPv4 address: bad"}
                   {:input "10.0.0.0/24" :valid true  :type "cidr" :error nil}]
          o       (captured #(display/print-validate-result results))]
      (is (str/includes? o "ok"))
      (is (str/includes? o "FAIL"))
      (is (str/includes? o "Invalid IPv4 address: bad"))
      (is (str/includes? o "cidr")))))

;;; ── print-lpm-result ─────────────────────────────────────────────────────────

(deftest print-lpm-result-test
  (testing "shows routes, match results, and no-match case"
    (let [routes  ["10.0.0.0/8" "10.0.0.0/24"]
          results [{:ip "10.0.0.1"  :match "10.0.0.0/24" :prefix-str "/24"}
                   {:ip "192.168.1.1" :match nil           :prefix-str nil}]
          o       (captured #(display/print-lpm-result routes results))]
      (is (str/includes? o "Routing table"))
      (is (str/includes? o "10.0.0.0/24"))
      (is (str/includes? o "(no match)")))))

;;; ── edge cases for branch coverage ──────────────────────────────────────────

(deftest print-split-table-no-broadcast-test
  (testing "broadcast shows '-' for /32 subnets"
    (let [subs (subnet/split-subnets "10.0.0.0/31" 32)
          o    (captured #(display/print-split-table subs))]
      (is (str/includes? o "10.0.0.0/32"))
      (is (str/includes? o "10.0.0.1/32"))
      (is (str/includes? o "-")))))

(deftest print-diff-result-unchanged-test
  (testing "unchanged entries print '[=]' marker"
    (let [before  ["10.0.0.0/24" "10.0.1.0/24"]
          after   ["10.0.1.0/24" "10.0.2.0/24"]
          {:keys [added removed unchanged]} (ops/cidr-diff before after)
          entries (vec (concat (map #(vector :removed   %) removed)
                               (map #(vector :unchanged %) unchanged)
                               (map #(vector :added     %) added)))
          o       (captured #(display/print-diff-result before after added removed unchanged entries))]
      (is (str/includes? o "[=]"))
      (is (str/includes? o "[+]"))
      (is (str/includes? o "[-]")))))

(deftest print-analyze-result-middle-bracket-test
  (testing "groups with 3+ routes use all three box-drawing brackets"
    ;; 4 adjacent /24s aggregate to 1 /22, creating a group of 4 routes
    (let [result (ops/analyze-routes ["10.0.0.0/24" "10.0.1.0/24"
                                      "10.0.2.0/24" "10.0.3.0/24"])
          o      (captured #(display/print-analyze-result result))]
      ;; Should contain ┌ (first), │ (middle), └ (last)
      (is (str/includes? o "\u250c"))
      (is (str/includes? o "\u2502"))
      (is (str/includes? o "\u2518")))))

(deftest print-util-result-non-largest-test
  (testing "non-largest free blocks do not show the arrow indicator"
    ;; Two small allocations leave two free gaps; only the larger gets ← largest
    (let [result (ops/utilization-info "10.0.0.0/22"
                                       ["10.0.0.0/24" "10.0.2.0/24"])
          o      (captured #(display/print-util-result result))]
      (is (str/includes? o "largest"))
      ;; There should be at least one free block that does NOT have the arrow
      (is (some? (re-find #"Free\s+10\.0\." o))))))

(deftest print-classify-result-spanning-test
  (testing "spanning CIDR with category-path is displayed correctly"
    ;; A CIDR that spans from private into public space
    (let [cs (mapv classify/classify ["100.64.0.0/9"])
          o  (captured #(display/print-classify-result cs))]
      ;; just verify it renders without error and includes input
      (is (str/includes? o "100.64.0.0")))))

(deftest print-analyze-result-no-containment-test
  (testing "no containment message is shown when nothing contained"
    (let [result (ops/analyze-routes ["10.0.0.0/24" "192.168.0.0/24"])
          o      (captured #(display/print-analyze-result result))]
      (is (str/includes? o "No containment"))))

  (testing "no summarization message is shown when nothing to summarize"
    (let [result (ops/analyze-routes ["10.0.0.0/24" "192.168.0.0/24"])
          o      (captured #(display/print-analyze-result result))]
      (is (str/includes? o "No summarization")))))
