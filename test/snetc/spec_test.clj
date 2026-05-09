(ns snetc.spec-test
  (:require [clojure.test            :refer [deftest is testing]]
            [clojure.spec.alpha      :as s]
            [clojure.spec.test.alpha :as stest]
            [snetc.spec     :as spec]
            [snetc.addr     :as addr]
            [snetc.ip       :as ip]
            [snetc.subnet   :as subnet]
            [snetc.classify :as classify]
            [snetc.ops      :as ops]))

(def ^:private num-tests 75)

(defn- check-sym
  "Runs stest/check for sym and returns true if all trials pass."
  [sym]
  (let [result (first (stest/check sym {:clojure.spec.test.check/opts {:num-tests num-tests}}))]
    (true? (:pass? (:clojure.spec.test.check/ret result)))))

;; ── Data spec conformance ─────────────────────────────────────────────────────

(deftest primitive-specs
  (testing "ip-long bounds"
    (is (s/valid? ::spec/ip-long 0))
    (is (s/valid? ::spec/ip-long 0xFFFFFFFF))
    (is (not (s/valid? ::spec/ip-long -1)))
    (is (not (s/valid? ::spec/ip-long 0x100000000))))

  (testing "prefix bounds"
    (is (s/valid? ::spec/prefix 0))
    (is (s/valid? ::spec/prefix 32))
    (is (not (s/valid? ::spec/prefix -1)))
    (is (not (s/valid? ::spec/prefix 33))))

  (testing "ip-str rejects leading zeros and out-of-range octets"
    (is (s/valid?     ::spec/ip-str "192.168.0.1"))
    (is (s/valid?     ::spec/ip-str "0.0.0.0"))
    (is (s/valid?     ::spec/ip-str "255.255.255.255"))
    (is (not (s/valid? ::spec/ip-str "010.0.0.1")))
    (is (not (s/valid? ::spec/ip-str "256.0.0.0")))
    (is (not (s/valid? ::spec/ip-str "1.2.3"))))

  (testing "cidr-str requires valid IP and prefix 0–32"
    (is (s/valid?     ::spec/cidr-str "10.0.0.0/8"))
    (is (s/valid?     ::spec/cidr-str "0.0.0.0/0"))
    (is (s/valid?     ::spec/cidr-str "1.2.3.4/32"))
    (is (not (s/valid? ::spec/cidr-str "10.0.0.0/33")))
    (is (not (s/valid? ::spec/cidr-str "10.0.0.0")))
    (is (not (s/valid? ::spec/cidr-str "010.0.0.0/8"))))

  (testing "address specs include IPv6"
    (is (s/valid?     ::spec/ipv6-str "2001:db8::1"))
    (is (s/valid?     ::spec/addr-str "10.0.0.1"))
    (is (s/valid?     ::spec/addr-str "2001:db8::1"))
    (is (s/valid?     ::spec/addr-cidr-str "10.0.0.0/24"))
    (is (s/valid?     ::spec/addr-cidr-str "2001:db8::/64"))
    (is (s/valid?     ::spec/same-family-cidrs ["2001:db8::/64" "2001:db8:0:1::/64"]))
    (is (not (s/valid? ::spec/same-family-cidrs ["10.0.0.0/24" "2001:db8::/64"])))
    (is (not (s/valid? ::spec/ipv6-str "2001:db8::g")))
    (is (not (s/valid? ::spec/addr-cidr-str "2001:db8::/129")))))

(deftest compound-specs
  (testing "subnet-info-map"
    (is (s/valid? ::spec/subnet-info-map
                  {:network "192.168.0.0" :broadcast "192.168.3.255"
                   :first-host "192.168.0.1" :last-host "192.168.3.254"
                   :hosts 1022 :mask "255.255.252.0" :wildcard "0.0.3.255"
                   :prefix 22 :cidr "192.168.0.0/22"})))

  (testing "addr-info-map supports IPv4 and IPv6"
    (is (s/valid? ::spec/addr-info-map (addr/subnet-info "10.0.0.0/24")))
    (is (s/valid? ::spec/addr-info-map (addr/subnet-info "2001:db8::/64"))))

  (testing "addr-range-map supports IPv4 and IPv6 ranges"
    (is (s/valid? ::spec/addr-range-map (addr/cidr->range "10.0.0.0/24")))
    (is (s/valid? ::spec/addr-range-map (addr/cidr->range "2001:db8::/64"))))

  (testing "prefix-allocation supports IPv6 prefix plans"
    (is (s/valid? ::spec/prefix-allocation
                  (first (ops/plan-prefixes "2001:db8::/60" [64])))))

  (testing "classify-result without :bcast-name"
    (is (s/valid? ::spec/classify-result
                  {:input "10.0.0.1" :name "Private" :rfc "RFC 1918"
                   :routable? false :spans? false})))

  (testing "classify-result with :bcast-name"
    (is (s/valid? ::spec/classify-result
                  {:input "10.0.0.0/7" :name "Private" :rfc "RFC 1918"
                   :routable? false :spans? true :bcast-name "Public"})))

  (testing "overlap-result"
    (is (s/valid? ::spec/overlap-result
                  {:a "10.0.0.0/8" :b "10.0.0.0/24" :type :a-contains-b}))
    (is (s/valid? ::spec/overlap-result
                  {:a "2001:db8::/63" :b "2001:db8::/64" :type :a-contains-b})))

  (testing "cidr-diff-result"
    (is (s/valid? ::spec/cidr-diff-result
                  {:added ["10.0.1.0/24"] :removed [] :unchanged ["10.0.0.0/24"]}))))

;; ── Generative function checks ────────────────────────────────────────────────

(deftest addr-generative
  (testing "valid-prefix? returns boolean"
    (is (check-sym `addr/valid-prefix?)))

  (testing "parse-ip returns family-aware address maps"
    (is (check-sym `addr/parse-ip)))

  (testing "parse-cidr returns family-aware CIDR maps"
    (is (check-sym `addr/parse-cidr)))

  (testing "subnet-info returns family-aware subnet info"
    (is (check-sym `addr/subnet-info)))

  (testing "cidr->range returns family-aware inclusive ranges"
    (is (check-sym `addr/cidr->range)))

  (testing "range->cidrs returns valid CIDR vectors"
    (is (check-sym `addr/range->cidrs)))

  (testing "address->text returns valid address strings"
    (is (check-sym `addr/address->text)))

  (testing "ip-in-cidr? returns boolean for matching families"
    (is (check-sym `addr/ip-in-cidr?)))

  (testing "split-subnets returns family-aware subnet info"
    (is (check-sym `addr/split-subnets)))

  (testing "adjacent-cidr returns a valid same-family CIDR"
    (is (check-sym `addr/adjacent-cidr)))

  (testing "subnet-tree returns family-aware tree nodes"
    (is (check-sym `addr/subnet-tree))))

(deftest ip-generative
  (testing "ip->long / long->ip round-trip"
    (is (check-sym `ip/ip->long))
    (is (check-sym `ip/long->ip)))

  (testing "prefix->mask round-trips through mask->prefix"
    (is (check-sym `ip/prefix->mask)))

  (testing "wildcard-mask complements prefix->mask to 0xFFFFFFFF"
    (is (check-sym `ip/wildcard-mask)))

  (testing "network-addr is idempotent"
    (is (check-sym `ip/network-addr)))

  (testing "broadcast-addr is always >= network"
    (is (check-sym `ip/broadcast-addr)))

  (testing "usable-hosts is always positive"
    (is (check-sym `ip/usable-hosts))))

(deftest subnet-generative
  (testing "parse-cidr returns well-typed map"
    (is (check-sym `subnet/parse-cidr)))

  (testing "subnet-info: prefix matches, network <= broadcast"
    (is (check-sym `subnet/subnet-info)))

  (testing "cidr->range: start is network addr, end is broadcast addr"
    (is (check-sym `subnet/cidr->range)))

  (testing "range->cidrs: nil iff start > end"
    (is (check-sym `subnet/range->cidrs)))

  (testing "ip-in-cidr? returns boolean"
    (is (check-sym `subnet/ip-in-cidr?))))

(deftest classify-generative
  (testing "classify returns well-typed result with :input echoed"
    (is (check-sym `classify/classify))))

(deftest ops-generative
  (testing "hosts->min-prefix: returned prefix fits the requested count"
    (is (check-sym `ops/hosts->min-prefix)))

  (testing "aggregate returns valid CIDRs"
    (is (check-sym `ops/aggregate)))

  (testing "free-space returns valid CIDRs"
    (is (check-sym `ops/free-space)))

  (testing "cidr-diff returns well-typed result"
    (is (check-sym `ops/cidr-diff)))

  (testing "find-overlaps returns well-typed results"
    (is (check-sym `ops/find-overlaps)))

  (testing "longest-prefix-match returns a CIDR string or nil"
    (is (check-sym `ops/longest-prefix-match)))

  (testing "supernet returns a valid covering CIDR"
    (is (check-sym `ops/supernet)))

  (testing "next-available-prefix returns a valid CIDR or nil"
    (is (check-sym `ops/next-available-prefix)))

  (testing "plan-prefixes returns prefix allocation maps"
    (is (check-sym `ops/plan-prefixes)))

  (testing "utilization-info returns allocation statistics"
    (is (check-sym `ops/utilization-info))))
