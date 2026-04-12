(ns snetc.ops-test
  (:require [clojure.test :refer [deftest is testing]]
            [snetc.subnet :refer [cidr->range]]
            [snetc.ops    :refer [aggregate free-space cidr-diff
                                  hosts->min-prefix plan-vlsm
                                  find-overlaps longest-prefix-match]]))

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
      (is (= (aggregate cidrs) (aggregate (aggregate cidrs)))))))

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
      (is (= pe ae)))))

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
    (is (= 3 (count (find-overlaps ["10.0.0.0/8" "10.0.0.0/16" "10.0.0.0/24"]))))))

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
    (is (= "192.168.0.0/24" (longest-prefix-match "192.168.0.255" ["192.168.0.0/24"])))))

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
      (is (empty?             (:unchanged r))))))
