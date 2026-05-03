(ns snetc.addr-test
  (:require [clojure.test :refer [deftest is testing]]
            [snetc.addr :as addr]))

(deftest parse-ip-test
  (testing "IPv4 parses through the family-aware layer"
    (is (= {:family :ipv4 :bits 32 :addr 167772161N :text "10.0.0.1"}
           (addr/parse-ip "10.0.0.1"))))

  (testing "IPv6 addresses are canonicalized"
    (is (= "2001:db8::1" (:text (addr/parse-ip "2001:0db8:0:0:0:0:0:1"))))
    (is (= "::1" (:text (addr/parse-ip "0:0:0:0:0:0:0:1"))))
    (is (= "::" (:text (addr/parse-ip "0:0:0:0:0:0:0:0")))))

  (testing "embedded IPv4 suffixes are accepted and normalized"
    (is (= "2001:db8::c000:201" (:text (addr/parse-ip "2001:db8::192.0.2.1"))))
    (is (= "::ffff:c000:201" (:text (addr/parse-ip "::ffff:192.0.2.1")))))

  (testing "invalid addresses throw"
    (is (thrown? Exception (addr/parse-ip "010.0.0.1")))
    (is (thrown? Exception (addr/parse-ip "2001:db8::g")))
    (is (thrown? Exception (addr/parse-ip "1:2:3:4:5:6:7:8:9")))
    (is (thrown? Exception (addr/parse-ip "fe80::1%en0")))))

(deftest parse-cidr-test
  (testing "IPv4 CIDR normalizes host bits"
    (let [parsed (addr/parse-cidr "192.168.1.5/24")]
      (is (= :ipv4 (:family parsed)))
      (is (= "192.168.1.0/24" (:cidr parsed)))))

  (testing "IPv6 CIDR normalizes host bits"
    (let [parsed (addr/parse-cidr "2001:db8::1/64")]
      (is (= :ipv6 (:family parsed)))
      (is (= 64 (:prefix parsed)))
      (is (= "2001:db8::/64" (:cidr parsed)))))

  (testing "prefix bounds are family-specific"
    (is (addr/valid-prefix? :ipv4 32))
    (is (not (addr/valid-prefix? :ipv4 33)))
    (is (addr/valid-prefix? :ipv6 128))
    (is (not (addr/valid-prefix? :ipv6 129)))
    (is (thrown? Exception (addr/parse-cidr "10.0.0.0/33")))
    (is (thrown? Exception (addr/parse-cidr "2001:db8::/129")))))

(deftest subnet-info-test
  (testing "IPv4 subnet info preserves existing compatibility keys"
    (let [info (addr/subnet-info "10.0.0.0/24")]
      (is (= :ipv4 (:family info)))
      (is (= "10.0.0.0/24" (:cidr info)))
      (is (= "10.0.0.255" (:broadcast info)))
      (is (= "10.0.0.1" (:first-host info)))
      (is (= 254 (:hosts info)))))

  (testing "IPv6 subnet info exposes address range fields"
    (let [info (addr/subnet-info "2001:db8::1/64")]
      (is (= :ipv6 (:family info)))
      (is (= "2001:db8::/64" (:cidr info)))
      (is (= "2001:db8::" (:network info)))
      (is (= "2001:db8::" (:first-address info)))
      (is (= "2001:db8::ffff:ffff:ffff:ffff" (:last-address info)))
      (is (= 18446744073709551616N (:addresses info)))
      (is (not (contains? info :broadcast)))
      (is (not (contains? info :hosts))))))

(deftest ip-in-cidr-test
  (testing "IPv4 containment still works"
    (is (addr/ip-in-cidr? "10.0.0.1" "10.0.0.0/24"))
    (is (not (addr/ip-in-cidr? "10.0.1.1" "10.0.0.0/24"))))

  (testing "IPv6 containment works"
    (is (addr/ip-in-cidr? "2001:db8::ffff" "2001:db8::/64"))
    (is (not (addr/ip-in-cidr? "2001:db9::1" "2001:db8::/64"))))

  (testing "mixed families are rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Mixed address families"
          (addr/ip-in-cidr? "2001:db8::1" "10.0.0.0/24")))))

(deftest range-test
  (testing "cidr->range returns family-aware inclusive bounds"
    (is (= {:family :ipv4 :start 167772160N :end 167772415N}
           (addr/cidr->range "10.0.0.0/24")))
    (let [{:keys [family start end]} (addr/cidr->range "2001:db8::/112")]
      (is (= :ipv6 family))
      (is (= start (:network (addr/parse-cidr "2001:db8::/112"))))
      (is (= 65535N (- end start)))))

  (testing "range->cidrs returns minimal IPv4 CIDRs"
    (is (= ["10.0.0.0/25" "10.0.0.128/26"]
           (addr/range->cidrs :ipv4 167772160N 167772351N))))

  (testing "range->cidrs returns minimal IPv6 CIDRs"
    (let [{:keys [start end]} (addr/cidr->range "2001:db8::/112")]
      (is (= ["2001:db8::/112"]
             (addr/range->cidrs :ipv6 start end))))
    (is (= ["::1/128" "::2/127"]
           (addr/range->cidrs :ipv6 1N 3N))))

  (testing "range->cidrs returns nil when start is greater than end"
    (is (nil? (addr/range->cidrs :ipv6 3N 1N)))))

(deftest address-text-test
  (testing "numeric addresses format canonically"
    (is (= "10.0.0.1" (addr/address->text :ipv4 167772161N)))
    (is (= "2001:db8::1" (addr/address->text :ipv6 (:addr (addr/parse-ip "2001:db8::1"))))))

  (testing "out-of-range numeric addresses are rejected"
    (is (thrown? Exception (addr/address->text :ipv4 0x100000000N)))))
