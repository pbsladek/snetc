(ns snetc.ops-test
  (:require [clojure.test :refer [deftest is testing]]
            [snetc.subnet :refer [cidr->range]]
            [snetc.ops    :refer [aggregate free-space cidr-diff
                                  hosts->min-prefix next-available next-available-prefix
                                  plan-vlsm plan-prefixes
                                  find-overlaps longest-prefix-match
                                  supernet utilization-info
                                  analyze-routes parse-routes]]))

;;; ── aggregate ────────────────────────────────────────────────────────────────

(deftest aggregate-test
  (testing "empty input returns empty"
    (is (= [] (aggregate []))))

  (testing "single network is returned unchanged"
    (is (= ["10.0.0.0/24"] (aggregate ["10.0.0.0/24"]))))

  (testing "adjacent /24s merge"
    (is (= ["10.0.0.0/23"]
           (aggregate ["10.0.0.0/24" "10.0.1.0/24"]))))

  (testing "four adjacent /24s merge into a /22"
    (is (= ["10.0.0.0/22"]
           (aggregate ["10.0.0.0/24" "10.0.1.0/24" "10.0.2.0/24" "10.0.3.0/24"]))))

  (testing "contained block is absorbed by the larger block"
    (is (= ["10.0.0.0/22"]
           (aggregate ["10.0.0.0/22" "10.0.0.0/24"]))))

  (testing "overlapping blocks merge to their minimal covering CIDR"
    (is (= ["10.0.0.0/23"]
           (aggregate ["10.0.0.0/24" "10.0.0.128/25" "10.0.1.0/24"]))))

  (testing "non-adjacent blocks stay separate"
    (is (= ["192.168.0.0/24" "192.168.2.0/24"]
           (aggregate ["192.168.0.0/24" "192.168.2.0/24"]))))

  (testing "input order does not affect output"
    (is (= (aggregate ["10.0.0.0/24" "10.0.1.0/24"])
           (aggregate ["10.0.1.0/24" "10.0.0.0/24"]))))

  (testing "aggregate is idempotent"
    (let [cidrs ["10.0.0.0/24" "10.0.1.0/24" "192.168.0.0/22" "172.16.0.0/12"]]
      (is (= (aggregate cidrs) (aggregate (aggregate cidrs))))))

  (testing "adjacent IPv6 networks merge"
    (is (= ["2001:db8::/63"]
           (aggregate ["2001:db8::/64" "2001:db8:0:1::/64"]))))

  (testing "mixed address families are rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"single address family"
          (aggregate ["10.0.0.0/24" "2001:db8::/64"])))))

;;; ── free-space ───────────────────────────────────────────────────────────────

(deftest free-space-test
  (testing "no allocations → entire parent is free"
    (is (= ["192.168.0.0/22"] (free-space "192.168.0.0/22" []))))

  (testing "fully allocated → no free space"
    (is (= [] (free-space "192.168.0.0/24" ["192.168.0.0/24"]))))

  (testing "one /24 allocated at start leaves the rest free"
    (is (= ["192.168.1.0/24" "192.168.2.0/23"]
           (free-space "192.168.0.0/22" ["192.168.0.0/24"]))))

  (testing "block in the middle leaves gaps on both sides"
    ;; 10.0.0.64/26 occupies .64–.127; leaves .0–.63 and .128–.255
    (is (= ["10.0.0.0/26" "10.0.0.128/25"]
           (free-space "10.0.0.0/24" ["10.0.0.64/26"]))))

  (testing "allocated blocks outside the parent are silently ignored"
    (is (= ["192.168.0.0/22"]
           (free-space "192.168.0.0/22" ["10.0.0.0/8"]))))

  (testing "multiple allocations leave one gap"
    (is (= ["192.168.1.0/24"]
           (free-space "192.168.0.0/22" ["192.168.0.0/24" "192.168.2.0/23"]))))

  (testing "allocation extending beyond parent boundary is clipped, not dropped"
    ;; 192.168.3.0/23 extends into 192.168.4.x, outside the /22 parent.
    ;; The overlap (192.168.3.0/24) must be treated as allocated, not free.
    (let [free (free-space "192.168.0.0/22" ["192.168.3.0/23"])]
      (is (not (some #(= "192.168.3.0/24" %) free)))
      ;; 192.168.0.0/23 should be the only free block
      (is (= ["192.168.0.0/23"] free))))

  (testing "free + allocated = parent (address-space union invariant)"
    (let [parent    "10.0.0.0/22"
          allocated ["10.0.0.0/24" "10.0.2.0/24"]
          free      (free-space parent allocated)
          all       (aggregate (concat allocated free))
          [ps pe]   (cidr->range parent)
          [as ae]   (cidr->range (first all))]
      (is (= 1  (count all)))
      (is (= ps as))
      (is (= pe ae))))

  (testing "IPv6 free space subtracts same-family allocations"
    (is (= ["2001:db8:0:1::/64"]
           (free-space "2001:db8::/63" ["2001:db8::/64"]))))

  (testing "IPv6 allocations outside parent are ignored"
    (is (= ["2001:db8::/64"]
           (free-space "2001:db8::/64" ["2001:db9::/64"]))))

  (testing "mixed address families are rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"single address family"
          (free-space "2001:db8::/64" ["10.0.0.0/24"])))))

;;; ── hosts->min-prefix ────────────────────────────────────────────────────────

(deftest hosts->min-prefix-test
  (testing "boundary values around prefix transitions"
    (is (= 32 (hosts->min-prefix 1)))
    (is (= 31 (hosts->min-prefix 2)))   ; /31 gives exactly 2 (RFC 3021)
    (is (= 29 (hosts->min-prefix 6)))   ; /30 gives 2, /29 gives 6
    (is (= 28 (hosts->min-prefix 14)))
    (is (= 24 (hosts->min-prefix 254)))
    (is (= 23 (hosts->min-prefix 255))) ; /24 gives 254 < 255, need /23 (510)
    (is (= 23 (hosts->min-prefix 510)))
    (is (= 22 (hosts->min-prefix 511)))) ; /23 gives 510 < 511, need /22 (1022)

  (testing "invalid input throws"
    (is (thrown? Exception (hosts->min-prefix 0)))
    (is (thrown? Exception (hosts->min-prefix -1)))))

;;; ── plan-vlsm ────────────────────────────────────────────────────────────────

(deftest plan-vlsm-test
  (testing "sorted largest-first, each block gets the tightest fitting prefix"
    (let [result (plan-vlsm "192.168.0.0/22" [500 200 50 10])]
      (is (= 4 (count result)))
      ;; 500 → /23 (510 hosts), 200 → /24 (254), 50 → /26 (62), 10 → /28 (14)
      (is (= [23 24 26 28] (map #(:prefix (:info %)) result)))
      (is (= [500 200 50 10] (map :requested result)))))

  (testing "all allocations lie within the parent"
    (let [[pstart pend] (cidr->range "192.168.0.0/22")]
      (doseq [{:keys [info]} (plan-vlsm "192.168.0.0/22" [500 200 50 10])]
        (let [[s e] (cidr->range (:cidr info))]
          (is (>= s pstart))
          (is (<= e pend))))))

  (testing "allocations do not overlap"
    (let [ranges (map #(cidr->range (:cidr (:info %)))
                      (plan-vlsm "192.168.0.0/22" [500 200 50 10]))]
      (doseq [[[s1 e1] [s2 e2]] (for [[i r1] (map-indexed vector ranges)
                                      [j r2] (map-indexed vector ranges)
                                      :when (< i j)]
                                  [r1 r2])]
        (is (or (> s1 e2) (> s2 e1))))))

  (testing "single allocation"
    (let [[r] (plan-vlsm "10.0.0.0/24" [100])]
      (is (= 25  (:prefix (:info r))))
      (is (= 100 (:requested r)))))

  (testing "throws when a single subnet won't fit in the parent"
    (is (thrown? Exception (plan-vlsm "10.0.0.0/30" [1000]))))

  (testing "throws when combined allocation exceeds parent"
    (is (thrown? Exception (plan-vlsm "10.0.0.0/28" [200])))))

;;; ── find-overlaps ────────────────────────────────────────────────────────────

(deftest find-overlaps-test
  (testing "disjoint networks produce no overlaps"
    (is (empty? (find-overlaps ["10.0.0.0/24" "10.0.1.0/24" "192.168.0.0/16"]))))

  (testing "adjacent networks do not overlap"
    (is (empty? (find-overlaps ["10.0.0.0/25" "10.0.0.128/25"]))))

  (testing ":a-contains-b when first CIDR wholly contains second"
    (let [[overlap] (find-overlaps ["10.0.0.0/8" "10.0.0.0/24"])]
      (is (= "10.0.0.0/8"  (:a overlap)))
      (is (= "10.0.0.0/24" (:b overlap)))
      (is (= :a-contains-b (:type overlap)))))

  (testing ":b-contains-a when second CIDR wholly contains first"
    (let [[overlap] (find-overlaps ["10.0.0.0/24" "10.0.0.0/8"])]
      (is (= :b-contains-a (:type overlap)))))

  (testing "identical CIDRs are reported as :a-contains-b"
    (let [[overlap] (find-overlaps ["10.0.0.0/24" "10.0.0.0/24"])]
      (is (= :a-contains-b (:type overlap)))))

  (testing "partial overlap between two CIDRs"
    ;; 10.0.0.0/24 (.0–.255) and 10.0.0.128/25 (.128–.255): partial (b inside a)
    (let [[overlap] (find-overlaps ["10.0.0.0/24" "10.0.0.192/26"])]
      (is (= :a-contains-b (:type overlap)))))

  (testing "N networks with N*(N-1)/2 pairwise overlaps"
    ;; /8 contains /16 contains /24 → 3 pairs
    (is (= 3 (count (find-overlaps ["10.0.0.0/8" "10.0.0.0/16" "10.0.0.0/24"])))))

  (testing "IPv6 overlaps are reported"
    (let [[overlap] (find-overlaps ["2001:db8::/63" "2001:db8::/64"])]
      (is (= "2001:db8::/63" (:a overlap)))
      (is (= "2001:db8::/64" (:b overlap)))
      (is (= :a-contains-b (:type overlap)))))

  (testing "mixed address families are rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"single address family"
          (find-overlaps ["10.0.0.0/24" "2001:db8::/64"])))))

;;; ── longest-prefix-match ─────────────────────────────────────────────────────

(deftest longest-prefix-match-test
  (let [routes ["10.0.0.0/8" "10.0.0.0/24" "192.168.0.0/16" "0.0.0.0/0"]]

    (testing "most specific (longest prefix) wins"
      (is (= "10.0.0.0/24" (longest-prefix-match "10.0.0.1" routes))))

    (testing "falls back to less-specific when no exact match"
      (is (= "10.0.0.0/8" (longest-prefix-match "10.1.0.1" routes))))

    (testing "default route 0.0.0.0/0 matches any IP"
      (is (= "0.0.0.0/0" (longest-prefix-match "8.8.8.8" routes))))

    (testing "returns nil when no route matches"
      (is (nil? (longest-prefix-match "8.8.8.8" ["10.0.0.0/8" "192.168.0.0/16"]))))

    (testing "/32 host route beats all less-specific routes"
      (is (= "10.0.0.5/32"
             (longest-prefix-match "10.0.0.5" ["10.0.0.0/24" "10.0.0.5/32" "10.0.0.0/8"])))))

  (testing "network address and broadcast address of a block both match"
    (is (= "192.168.0.0/24" (longest-prefix-match "192.168.0.0"   ["192.168.0.0/24"])))
    (is (= "192.168.0.0/24" (longest-prefix-match "192.168.0.255" ["192.168.0.0/24"]))))

  (testing "IPv6 longest prefix match"
    (is (= "2001:db8::/64"
           (longest-prefix-match "2001:db8::1"
                                 ["2001:db8::/32" "2001:db8::/64"]))))

  (testing "mixed route and lookup families are rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"single address family"
          (longest-prefix-match "2001:db8::1" ["10.0.0.0/8"])))))

;;; ── supernet ─────────────────────────────────────────────────────────────────

(deftest supernet-test
  (testing "two adjacent /24s → /23"
    (is (= "10.0.0.0/23" (supernet ["10.0.0.0/24" "10.0.1.0/24"]))))

  (testing "same CIDR twice → that CIDR"
    (is (= "10.0.0.0/24" (supernet ["10.0.0.0/24" "10.0.0.0/24"]))))

  (testing "one block already contains the other → containing block"
    (is (= "10.0.0.0/8" (supernet ["10.0.0.0/8" "10.1.2.0/24"]))))

  (testing "non-aligned inputs require a wider covering block"
    ;; .0/24 and .5/24 span .0–.5 so the tightest cover is .0/21
    (is (= "192.168.0.0/21" (supernet ["192.168.0.0/24" "192.168.5.0/24"]))))

  (testing "three inputs"
    (is (= "192.168.0.0/21"
           (supernet ["192.168.0.0/24" "192.168.5.0/24" "192.168.3.0/24"]))))

  (testing "result fully contains every input"
    (let [inputs ["10.0.0.0/24" "10.0.3.0/24" "10.0.7.0/24"]
          result (supernet inputs)
          [rs re] (cidr->range result)]
      (doseq [c inputs]
        (let [[s e] (cidr->range c)]
          (is (<= rs s))
          (is (>= re e))))))

  (testing "IPv6 adjacent networks produce the covering supernet"
    (is (= "2001:db8::/63"
           (supernet ["2001:db8::/64" "2001:db8:0:1::/64"]))))

  (testing "mixed address families are rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"single address family"
          (supernet ["10.0.0.0/24" "2001:db8::/64"])))))

;;; ── cidr-diff ────────────────────────────────────────────────────────────────

(deftest cidr-diff-test
  (testing "both empty → all fields empty"
    (let [r (cidr-diff [] [])]
      (is (empty? (:added     r)))
      (is (empty? (:removed   r)))
      (is (empty? (:unchanged r)))))

  (testing "one block added"
    (let [r (cidr-diff ["10.0.0.0/24"] ["10.0.0.0/24" "10.0.1.0/24"])]
      (is (= ["10.0.1.0/24"] (:added     r)))
      (is (= ["10.0.0.0/24"] (:unchanged r)))
      (is (empty?             (:removed   r)))))

  (testing "one block removed"
    (let [r (cidr-diff ["10.0.0.0/24" "10.0.1.0/24"] ["10.0.0.0/24"])]
      (is (empty?             (:added     r)))
      (is (= ["10.0.0.0/24"] (:unchanged r)))
      (is (= ["10.0.1.0/24"] (:removed   r)))))

  (testing "equivalent representations show no diff (aggregated before comparison)"
    (let [r (cidr-diff ["10.0.0.0/22"]
                       ["10.0.0.0/24" "10.0.1.0/24" "10.0.2.0/24" "10.0.3.0/24"])]
      (is (empty? (:added   r)))
      (is (empty? (:removed r)))
      (is (= ["10.0.0.0/22"] (:unchanged r)))))

  (testing "completely replaced"
    (let [r (cidr-diff ["10.0.0.0/24"] ["192.168.0.0/24"])]
      (is (= ["192.168.0.0/24"] (:added   r)))
      (is (= ["10.0.0.0/24"]    (:removed r)))
      (is (empty?                (:unchanged r)))))

  (testing "partial overlap — part removed, part added, part unchanged"
    (let [r (cidr-diff ["10.0.0.0/23"] ["10.0.1.0/24" "10.0.2.0/23"])]
      (is (= ["10.0.2.0/23"] (:added   r)))
      (is (= ["10.0.0.0/24"] (:removed r)))
      (is (= ["10.0.1.0/24"] (:unchanged r)))))

  (testing "empty before → everything added"
    (let [r (cidr-diff [] ["10.0.0.0/24"])]
      (is (= ["10.0.0.0/24"] (:added   r)))
      (is (empty?             (:removed r)))
      (is (empty?             (:unchanged r)))))

  (testing "empty after → everything removed"
    (let [r (cidr-diff ["10.0.0.0/24"] [])]
      (is (empty?             (:added   r)))
      (is (= ["10.0.0.0/24"] (:removed r)))
      (is (empty?             (:unchanged r)))))

  (testing "IPv6 diff reports added, removed, and unchanged"
    (let [r (cidr-diff ["2001:db8::/63"]
                       ["2001:db8::/64" "2001:db8:0:2::/64"])]
      (is (= ["2001:db8:0:2::/64"] (:added r)))
      (is (= ["2001:db8:0:1::/64"] (:removed r)))
      (is (= ["2001:db8::/64"] (:unchanged r)))))

  (testing "mixed address families are rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"single address family"
          (cidr-diff ["10.0.0.0/24"] ["2001:db8::/64"])))))

;;; ── next-available ───────────────────────────────────────────────────────────

(deftest next-available-test
  (testing "returns first aligned block for n hosts when nothing allocated"
    (is (= "10.0.0.0/25" (next-available "10.0.0.0/24" [] 100))))

  (testing "skips allocated blocks"
    (is (= "10.0.0.128/25"
           (next-available "10.0.0.0/24" ["10.0.0.0/25"] 100))))

  (testing "result fits within parent"
    (let [[pstart pend] (cidr->range "192.168.0.0/22")
          result        (next-available "192.168.0.0/22" [] 500)
          [rs re]       (cidr->range result)]
      (is (>= rs pstart))
      (is (<= re pend))))

  (testing "returns nil when no block fits"
    (is (nil? (next-available "10.0.0.0/30" ["10.0.0.0/30"] 100))))

  (testing "single host gets /32"
    (is (= "10.0.0.0/32" (next-available "10.0.0.0/24" [] 1)))))

(deftest next-available-prefix-test
  (testing "returns first aligned IPv6 prefix block"
    (is (= "2001:db8::/64"
           (next-available-prefix "2001:db8::/60" [] 64))))

  (testing "skips used IPv6 prefixes"
    (is (= "2001:db8:0:1::/64"
           (next-available-prefix "2001:db8::/60" ["2001:db8::/64"] 64))))

  (testing "returns nil when no requested prefix fits"
    (is (nil? (next-available-prefix "2001:db8::/64" ["2001:db8::/64"] 64)))))

(deftest plan-prefixes-test
  (testing "allocates IPv6 prefixes largest-first"
    (let [result (plan-prefixes "2001:db8::/60" [64 64])]
      (is (= ["2001:db8::/64" "2001:db8:0:1::/64"]
             (mapv #(-> % :info :cidr) result)))
      (is (= ["/64" "/64"] (mapv :requested result)))))

  (testing "rejects a requested prefix wider than the parent"
    (is (thrown? Exception (plan-prefixes "2001:db8::/64" [63]))))

  (testing "throws when combined prefix requests exceed the parent"
    (is (thrown? Exception (plan-prefixes "2001:db8::/127" [128 128 128])))))

;;; ── utilization-info ─────────────────────────────────────────────────────────

(deftest utilization-info-test
  (testing "returns correct stats for 50% utilization"
    (let [r (utilization-info "10.0.0.0/24" ["10.0.0.0/25"])]
      (is (= 256 (:total-addrs r)))
      (is (= 128 (:used-addrs  r)))
      (is (= 128 (:free-addrs  r)))
      (is (= 50  (:pct-used    r)))))

  (testing "fully allocated shows no free blocks"
    (let [r (utilization-info "10.0.0.0/24" ["10.0.0.0/24"])]
      (is (empty? (:free-infos r)))
      (is (nil?   (:fragmentation r)))))

  (testing "fragmentation score increases with more free blocks"
    (let [r1 (utilization-info "10.0.0.0/22" ["10.0.0.0/24" "10.0.2.0/24"])
          r4 (utilization-info "10.0.0.0/20"
                               ["10.0.0.0/24" "10.0.2.0/24" "10.0.4.0/24"
                                "10.0.6.0/24" "10.0.8.0/24" "10.0.10.0/24"
                                "10.0.12.0/24" "10.0.14.0/24"])]
      (is (= "low"  (:fragmentation r1)))
      (is (= "high" (:fragmentation r4)))))

  (testing "largest-free points to the biggest free block"
    (let [r (utilization-info "10.0.0.0/22" ["10.0.0.0/24"])]
      (is (some? (:largest-free r)))
      (is (= (:cidr (:largest-free r))
             (:cidr (apply max-key :hosts (:free-infos r)))))))

  (testing "bar string has the expected width"
    (let [r (utilization-info "10.0.0.0/24" ["10.0.0.0/25"])]
      (is (= 72 (count (:bar r))))))

  (testing "IPv6 utilization uses address counts and free blocks"
    (let [r (utilization-info "2001:db8::/63" ["2001:db8::/64"])]
      (is (= :ipv6 (:family (:parent-info r))))
      (is (= 36893488147419103232N (:total-addrs r)))
      (is (= 18446744073709551616N (:used-addrs r)))
      (is (= 18446744073709551616N (:free-addrs r)))
      (is (= 50 (:pct-used r)))
      (is (= "2001:db8:0:1::/64" (-> r :free-infos first :cidr))))))

;;; ── parse-routes ──────────────────────────────���──────────────────────────────

(deftest parse-routes-test
  (testing "extracts CIDRs from plain list"
    (is (= ["10.0.0.0/24" "192.168.0.0/16"]
           (parse-routes "10.0.0.0/24\n192.168.0.0/16\n"))))

  (testing "extracts CIDRs from ip-route output style"
    (let [text "10.0.0.0/24 dev eth0 proto kernel scope link src 10.0.0.5\n192.168.1.0/24 via 10.0.0.1 dev eth0"
          r    (parse-routes text)]
      (is (= 2 (count r)))
      (is (some #{"10.0.0.0/24"} r))
      (is (some #{"192.168.1.0/24"} r))))

  (testing "ignores blank lines and comments"
    (is (= ["10.0.0.0/24"]
           (parse-routes "# comment\n\n10.0.0.0/24\n"))))

  (testing "normalises host bits in CIDRs"
    (let [r (parse-routes "10.0.0.5/24\n")]
      (is (= ["10.0.0.0/24"] r))))

  (testing "extracts and normalises IPv6 CIDRs"
    (is (= ["2001:db8::/64" "2001:db8:0:1::/64"]
           (parse-routes "2001:db8::1/64 dev eth0\n2001:db8:0:1::/64 via fe80::1\n"))))

  (testing "returns distinct CIDRs"
    (is (= 1 (count (parse-routes "10.0.0.0/24\n10.0.0.0/24\n")))))

  (testing "returns empty vec when no CIDRs found"
    (is (= [] (parse-routes "no routes here\n")))))

;;; ── analyze-routes ───────────────────────────────────────────────────────────

(deftest analyze-routes-test
  (testing "fully optimized routes show zero savings"
    (let [r (analyze-routes ["10.0.0.0/24" "192.168.0.0/24"])]
      (is (= 2 (:route-count      r)))
      (is (= 2 (:aggregated-count r)))
      (is (= 0 (:savings          r)))
      (is (empty? (:groups    r)))
      (is (empty? (:contained r)))))

  (testing "summarizable routes show savings and groups"
    (let [r (analyze-routes ["10.0.0.0/24" "10.0.1.0/24"])]
      (is (= 2 (:route-count      r)))
      (is (= 1 (:aggregated-count r)))
      (is (= 1 (:savings          r)))
      (is (= 1 (count (:groups r))))
      (is (= "10.0.0.0/23" (:summary (first (:groups r)))))))

  (testing "contained routes are detected"
    (let [r (analyze-routes ["10.0.0.0/8" "10.0.0.0/24"])]
      (is (= 1 (count (:contained r))))))

  (testing "routes accessor preserves input"
    (let [routes ["10.0.0.0/24" "10.0.1.0/24"]
          r      (analyze-routes routes)]
      (is (= routes (:routes r)))))

  (testing "IPv6 summarizable routes show savings and groups"
    (let [r (analyze-routes ["2001:db8::/64" "2001:db8:0:1::/64"])]
      (is (= :ipv6 (:family r)))
      (is (= 2 (:route-count r)))
      (is (= 1 (:aggregated-count r)))
      (is (= 1 (:savings r)))
      (is (= "2001:db8::/63" (-> r :groups first :summary)))))

  (testing "mixed address families are rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"single address family"
          (analyze-routes ["10.0.0.0/24" "2001:db8::/64"])))))
