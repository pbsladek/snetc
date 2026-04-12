(ns snetc.subnet-test
  (:require [clojure.test :refer [deftest is testing]]
            [snetc.ip     :refer [ip->long]]
            [snetc.subnet :refer [valid-ip? valid-prefix? parse-cidr subnet-info
                                  split-subnets cidr->range range->cidrs ip-in-cidr?]]))

;;; ── valid-ip? ────────────────────────────────────────────────────────────────

(deftest valid-ip-test
  (testing "valid addresses return true (not just truthy)"
    (is (true?  (valid-ip? "0.0.0.0")))
    (is (true?  (valid-ip? "192.168.0.0")))
    (is (true?  (valid-ip? "255.255.255.255"))))

  (testing "invalid addresses return false (not just falsy)"
    (is (false? (valid-ip? "256.0.0.0")))
    (is (false? (valid-ip? "192.168.0")))
    (is (false? (valid-ip? "abc")))
    (is (false? (valid-ip? ""))))

  (testing "leading zeros are rejected (RFC 3986 §3.2.2)"
    (is (false? (valid-ip? "010.0.0.1")))
    (is (false? (valid-ip? "192.168.01.1")))
    (is (false? (valid-ip? "00.0.0.0")))
    (is (false? (valid-ip? "192.168.0.00")))))

;;; ── valid-prefix? ────────────────────────────────────────────────────────────

(deftest valid-prefix-test
  (is (valid-prefix? 0))
  (is (valid-prefix? 16))
  (is (valid-prefix? 32))
  (is (not (valid-prefix? -1)))
  (is (not (valid-prefix? 33))))

;;; ── parse-cidr ───────────────────────────────────────────────────────────────

(deftest parse-cidr-test
  (testing "valid input"
    (is (= {:ip-str "192.168.0.0" :prefix 22} (parse-cidr "192.168.0.0/22")))
    (is (= {:ip-str "10.0.0.0"    :prefix 8}  (parse-cidr "10.0.0.0/8")))
    (is (= {:ip-str "0.0.0.0"     :prefix 0}  (parse-cidr "0.0.0.0/0")))
    (is (= {:ip-str "1.2.3.4"     :prefix 32} (parse-cidr "1.2.3.4/32"))))

  (testing "host bits in ip-str are preserved (normalisation is subnet-info's job)"
    (is (= {:ip-str "192.168.1.5" :prefix 22} (parse-cidr "192.168.1.5/22"))))

  (testing "invalid input throws"
    (is (thrown? Exception (parse-cidr "bad/22")))
    (is (thrown? Exception (parse-cidr "192.168.0.0/33")))
    (is (thrown? Exception (parse-cidr "192.168.0.0/abc")))
    (is (thrown? Exception (parse-cidr "192.168.0.0")))))

;;; ── subnet-info ──────────────────────────────────────────────────────────────

(deftest subnet-info-test
  (testing "192.168.0.0/22"
    (let [info (subnet-info "192.168.0.0/22")]
      (is (= "192.168.0.0"    (:network    info)))
      (is (= "192.168.3.255"  (:broadcast  info)))
      (is (= "192.168.0.1"    (:first-host info)))
      (is (= "192.168.3.254"  (:last-host  info)))
      (is (= 1022             (:hosts      info)))
      (is (= "255.255.252.0"  (:mask       info)))
      (is (= "0.0.3.255"      (:wildcard   info)))
      (is (= 22               (:prefix     info)))
      (is (= "192.168.0.0/22" (:cidr       info)))))

  (testing "10.0.0.0/8"
    (let [info (subnet-info "10.0.0.0/8")]
      (is (= "10.0.0.0"       (:network    info)))
      (is (= "10.255.255.255" (:broadcast  info)))
      (is (= "10.0.0.1"       (:first-host info)))
      (is (= "10.255.255.254" (:last-host  info)))
      (is (= 16777214         (:hosts      info)))
      (is (= "255.0.0.0"      (:mask       info)))
      (is (= "0.255.255.255"  (:wildcard   info)))))

  (testing "/32 host route — network, broadcast, first, and last are all the same address"
    (let [info (subnet-info "192.168.1.1/32")]
      (is (= "192.168.1.1" (:network    info)))
      (is (= "192.168.1.1" (:broadcast  info)))
      (is (= "192.168.1.1" (:first-host info)))
      (is (= "192.168.1.1" (:last-host  info)))
      (is (= 1             (:hosts      info)))))

  (testing "/31 point-to-point — both addresses are usable (RFC 3021)"
    (let [info (subnet-info "10.0.0.0/31")]
      (is (= "10.0.0.0" (:network    info)))
      (is (= "10.0.0.1" (:broadcast  info)))
      (is (= "10.0.0.0" (:first-host info)))
      (is (= "10.0.0.1" (:last-host  info)))
      (is (= 2          (:hosts      info)))))

  (testing "host bits in input are masked off; :cidr reflects the normalised network"
    (let [info (subnet-info "192.168.1.5/22")]
      (is (= "192.168.0.0"    (:network info)))
      (is (= "192.168.0.0/22" (:cidr    info)))))

  (testing "throws on bad input"
    (is (thrown? Exception (subnet-info "notacidr")))))

;;; ── split-subnets ────────────────────────────────────────────────────────────

(deftest split-subnets-test
  (testing "split /22 into /24 yields 4 subnets with correct networks and prefix"
    (let [subs (split-subnets "192.168.0.0/22" 24)]
      (is (= 4 (count subs)))
      (is (every? #(= 24 (:prefix %)) subs))
      (is (= ["192.168.0.0" "192.168.1.0" "192.168.2.0" "192.168.3.0"]
             (map :network subs)))))

  (testing "split /24 into /25 yields 2 contiguous, non-overlapping subnets"
    (let [subs  (split-subnets "192.168.0.0/24" 25)
          rngs  (map #(cidr->range (:cidr %)) subs)]
      (is (= 2 (count subs)))
      (is (every? #(= 25 (:prefix %)) subs))
      ;; contiguous: end of first + 1 == start of second
      (is (= (inc (second (first rngs))) (first (second rngs))))))

  (testing "split /24 into /24 yields the subnet itself"
    (let [subs (split-subnets "10.0.0.0/24" 24)]
      (is (= 1 (count subs)))
      (is (= "10.0.0.0" (:network (first subs))))))

  (testing "all subnets lie within the parent range"
    (let [[pstart pend] (cidr->range "10.0.0.0/8")
          subs          (split-subnets "10.0.0.0/8" 10)]
      (doseq [[s e] (map #(cidr->range (:cidr %)) subs)]
        (is (>= s pstart))
        (is (<= e pend))))))

;;; ── cidr->range ──────────────────────────────────────────────────────────────

(deftest cidr->range-test
  (is (= [3232235520 3232236543] (cidr->range "192.168.0.0/22")))
  (is (= [167772160  184549375]  (cidr->range "10.0.0.0/8")))
  (is (= [(ip->long "1.2.3.4") (ip->long "1.2.3.4")] (cidr->range "1.2.3.4/32")))
  (is (= [0 4294967295]          (cidr->range "0.0.0.0/0"))))

;;; ── range->cidrs ─────────────────────────────────────────────────────────────

(deftest range->cidrs-test
  (testing "nil when start > end"
    (is (nil? (range->cidrs 10 9))))

  (testing "single host"
    (is (= ["10.0.0.1/32"]
           (range->cidrs (ip->long "10.0.0.1") (ip->long "10.0.0.1")))))

  (testing "aligned ranges produce a single CIDR"
    (is (= ["10.0.0.0/24"] (range->cidrs (ip->long "10.0.0.0") (ip->long "10.0.0.255"))))
    (is (= ["10.0.0.0/23"] (range->cidrs (ip->long "10.0.0.0") (ip->long "10.0.1.255"))))
    (is (= ["0.0.0.0/0"]   (range->cidrs 0 4294967295))))

  (testing "non-aligned ranges split into minimal CIDRs"
    (is (= ["10.0.0.0/25" "10.0.0.128/26"]
           (range->cidrs (ip->long "10.0.0.0") (ip->long "10.0.0.191"))))
    (is (= ["10.0.0.5/32" "10.0.0.6/31" "10.0.0.8/31" "10.0.0.10/32"]
           (range->cidrs (ip->long "10.0.0.5") (ip->long "10.0.0.10")))))

  (testing "output covers exactly [start, end] with no gaps and no overlap"
    (let [start (ip->long "10.0.0.5")
          end   (ip->long "10.0.1.200")
          rngs  (mapv cidr->range (range->cidrs start end))]
      (is (= start (first (first rngs))))
      (is (= end   (second (last rngs))))
      (is (every? (fn [[[_ e1] [s2 _]]] (= (inc e1) s2))
                  (partition 2 1 rngs))))))

;;; ── ip-in-cidr? ──────────────────────────────────────────────────────────────

(deftest ip-in-cidr-test
  (testing "network address, host, and broadcast are all inside"
    (is (ip-in-cidr? "192.168.0.0"   "192.168.0.0/22"))
    (is (ip-in-cidr? "192.168.1.100" "192.168.0.0/22"))
    (is (ip-in-cidr? "192.168.3.255" "192.168.0.0/22"))
    (is (ip-in-cidr? "10.0.0.1"      "10.0.0.0/8")))

  (testing "addresses just outside the boundary are not inside"
    (is (not (ip-in-cidr? "192.167.255.255" "192.168.0.0/22")))
    (is (not (ip-in-cidr? "192.168.4.0"     "192.168.0.0/22"))))

  (testing "completely different block"
    (is (not (ip-in-cidr? "10.0.0.1" "192.168.0.0/22")))))
