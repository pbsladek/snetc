(ns snetc.classify-test
  (:require [clojure.test   :refer [deftest is testing]]
            [snetc.classify :refer [classify]]))

;;; ── classify returns expected shape ─────────────────────────────────────────

(deftest classify-shape-test
  (testing "result always contains required keys"
    (let [r (classify "8.8.8.8")]
      (is (contains? r :input))
      (is (contains? r :name))
      (is (contains? r :rfc))
      (is (contains? r :routable?))
      (is (contains? r :spans?))))

  (testing ":input echoes the argument unchanged"
    (is (= "10.0.0.1"       (:input (classify "10.0.0.1"))))
    (is (= "192.168.0.0/16" (:input (classify "192.168.0.0/16"))))))

;;; ── special range classification ─────────────────────────────────────────────

(deftest classify-special-ranges-test
  (testing "RFC 1918 private ranges"
    (is (= {:name "Private" :rfc "RFC 1918"}
           (select-keys (classify "10.0.0.1") [:name :rfc])))
    (is (= {:name "Private" :rfc "RFC 1918"}
           (select-keys (classify "172.16.0.1") [:name :rfc])))
    (is (= {:name "Private" :rfc "RFC 1918"}
           (select-keys (classify "172.31.255.255") [:name :rfc])))
    (is (= {:name "Private" :rfc "RFC 1918"}
           (select-keys (classify "192.168.1.1") [:name :rfc]))))

  (testing "loopback (RFC 1122)"
    (is (= {:name "Loopback" :rfc "RFC 1122"}
           (select-keys (classify "127.0.0.1") [:name :rfc])))
    (is (= {:name "Loopback" :rfc "RFC 1122"}
           (select-keys (classify "127.255.255.255") [:name :rfc]))))

  (testing "link-local (RFC 3927)"
    (is (= {:name "Link-Local" :rfc "RFC 3927"}
           (select-keys (classify "169.254.0.1") [:name :rfc]))))

  (testing "multicast (RFC 1112)"
    (is (= {:name "Multicast" :rfc "RFC 1112"}
           (select-keys (classify "224.0.0.1") [:name :rfc])))
    (is (= {:name "Multicast" :rfc "RFC 1112"}
           (select-keys (classify "239.255.255.255") [:name :rfc]))))

  (testing "reserved (RFC 1112)"
    (is (= {:name "Reserved" :rfc "RFC 1112"}
           (select-keys (classify "240.0.0.1") [:name :rfc]))))

  (testing "limited broadcast (RFC 919) — not classified as Reserved"
    (is (= {:name "Limited Broadcast" :rfc "RFC 919"}
           (select-keys (classify "255.255.255.255") [:name :rfc]))))

  (testing "shared address space (RFC 6598)"
    (is (= {:name "Shared Address Space" :rfc "RFC 6598"}
           (select-keys (classify "100.64.0.1") [:name :rfc]))))

  (testing "benchmarking (RFC 2544)"
    (is (= {:name "Benchmarking" :rfc "RFC 2544"}
           (select-keys (classify "198.18.0.1") [:name :rfc]))))

  (testing "documentation ranges (RFC 5737)"
    (is (= {:name "Documentation TEST-NET-1" :rfc "RFC 5737"}
           (select-keys (classify "192.0.2.1") [:name :rfc])))
    (is (= {:name "Documentation TEST-NET-2" :rfc "RFC 5737"}
           (select-keys (classify "198.51.100.1") [:name :rfc])))
    (is (= {:name "Documentation TEST-NET-3" :rfc "RFC 5737"}
           (select-keys (classify "203.0.113.1") [:name :rfc]))))

  (testing "this network (RFC 1122)"
    (is (= {:name "This Network" :rfc "RFC 1122"}
           (select-keys (classify "0.0.0.0") [:name :rfc])))
    (is (= {:name "This Network" :rfc "RFC 1122"}
           (select-keys (classify "0.255.255.255") [:name :rfc]))))

  (testing "IETF protocol assignments (RFC 6890)"
    (is (= {:name "IETF Protocol Assignments" :rfc "RFC 6890"}
           (select-keys (classify "192.0.0.1") [:name :rfc])))
    (is (= {:name "IETF Protocol Assignments" :rfc "RFC 6890"}
           (select-keys (classify "192.0.0.255") [:name :rfc]))))

  (testing "range boundary edges"
    ;; 100.64.0.0/10 → 100.64.0.0–100.127.255.255
    (is (= "Shared Address Space" (:name (classify "100.64.0.0"))))
    (is (= "Shared Address Space" (:name (classify "100.127.255.255"))))
    (is (= "Public"               (:name (classify "100.63.255.255"))))
    (is (= "Public"               (:name (classify "100.128.0.0"))))
    ;; 198.18.0.0/15 → 198.18.0.0–198.19.255.255
    (is (= "Benchmarking" (:name (classify "198.18.0.0"))))
    (is (= "Benchmarking" (:name (classify "198.19.255.255"))))
    (is (= "Public"       (:name (classify "198.17.255.255"))))
    (is (= "Public"       (:name (classify "198.20.0.0"))))
    ;; 240.0.0.0/4 → 240.0.0.0–255.255.255.254  (255.255.255.255 is Limited Broadcast)
    (is (= "Reserved"         (:name (classify "240.0.0.0"))))
    (is (= "Reserved"         (:name (classify "255.255.255.254"))))
    (is (= "Limited Broadcast" (:name (classify "255.255.255.255")))))

  (testing "public/routable"
    (is (= {:name "Public" :rfc ""}
           (select-keys (classify "8.8.8.8") [:name :rfc])))))

(deftest classify-ipv6-special-ranges-test
  (testing "IPv6 special ranges"
    (is (= {:family :ipv6 :name "Loopback" :rfc "RFC 4291"}
           (select-keys (classify "::1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "Unspecified" :rfc "RFC 4291"}
           (select-keys (classify "::") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "IPv4-mapped" :rfc "RFC 4291"}
           (select-keys (classify "::ffff:192.0.2.1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "IPv4-IPv6 Translation" :rfc "RFC 6052"}
           (select-keys (classify "64:ff9b::1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "Discard-Only" :rfc "RFC 6666"}
           (select-keys (classify "100::1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "AMT" :rfc "RFC 7450"}
           (select-keys (classify "2001:3::1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "ORCHIDv2" :rfc "RFC 7343"}
           (select-keys (classify "2001:20::1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "Documentation" :rfc "RFC 3849"}
           (select-keys (classify "2001:db8::1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "Documentation" :rfc "RFC 9637"}
           (select-keys (classify "3fff::1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "6to4" :rfc "RFC 3056"}
           (select-keys (classify "2002::1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "Unique Local" :rfc "RFC 4193"}
           (select-keys (classify "fc00::1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "Link-Local" :rfc "RFC 4291"}
           (select-keys (classify "fe80::1") [:family :name :rfc])))
    (is (= {:family :ipv6 :name "Multicast" :rfc "RFC 4291/RFC 3307"}
           (select-keys (classify "ff00::1") [:family :name :rfc]))))

  (testing "IANA reserved address-space ranges are not public"
    (is (= {:family :ipv6 :name "Reserved by IETF" :rfc "RFC 3513/RFC 4291"}
           (select-keys (classify "4000::1") [:family :name :rfc])))
    (is (false? (:routable? (classify "100:0:0:2::1")))))

  (testing "IPv6 public addresses remain routable"
    (let [r (classify "2001:4860:4860::8888")]
      (is (= "Public" (:name r)))
      (is (true? (:routable? r))))))

;;; ── routable? flag ───────────────────────────────────────────────────────────

(deftest classify-routable-test
  (testing "public addresses are routable"
    (is (true? (:routable? (classify "8.8.8.8"))))
    (is (true? (:routable? (classify "1.1.1.1")))))

  (testing "special-use addresses are not routable"
    (is (false? (:routable? (classify "10.0.0.1"))))
    (is (false? (:routable? (classify "172.16.0.1"))))
    (is (false? (:routable? (classify "192.168.1.1"))))
    (is (false? (:routable? (classify "127.0.0.1"))))
    (is (false? (:routable? (classify "169.254.0.1"))))
    (is (false? (:routable? (classify "224.0.0.1"))))
    (is (false? (:routable? (classify "255.255.255.255"))))))

;;; ── CIDR classification ──────────────────────────────────────────────────────

(deftest classify-cidr-test
  (testing "CIDR is classified by its network address"
    (is (= "Private" (:name (classify "10.0.0.0/8"))))
    (is (= "Loopback" (:name (classify "127.0.0.0/8")))))

  (testing ":spans? is false when entire block falls within one category"
    (is (false? (:spans? (classify "192.168.0.0/16"))))
    (is (false? (:spans? (classify "10.0.0.0/8")))))

  (testing ":spans? is true when network and broadcast fall in different categories"
    ;; 10.0.0.0/7 covers 10.x (Private) and 11.x (Public)
    (let [r (classify "10.0.0.0/7")]
      (is (true? (:spans? r)))
      (is (= "Private" (:name r)))
      (is (= "Public"  (:bcast-name r)))))

  (testing ":spans? detects special ranges embedded between public endpoints"
    ;; 172.0.0.0/7 starts and ends in public space but contains 172.16.0.0/12.
    (let [r (classify "172.0.0.0/7")]
      (is (true? (:spans? r)))
      (is (= "Public" (:name r)))
      (is (false? (:routable? r)))
      (is (= "Private → Public" (:bcast-name r)))
      (is (= ["Public" "Private" "Public"]
             (map :name (:category-path r))))))

  (testing ":bcast-name is absent when :spans? is false"
    (is (not (contains? (classify "10.0.0.0/8") :bcast-name)))))

(deftest classify-ipv6-cidr-test
  (testing "IPv6 CIDRs classify by network address and detect embedded category changes"
    (let [r (classify "2001:db8::/31")]
      (is (= :ipv6 (:family r)))
      (is (= "Documentation" (:name r)))
      (is (true? (:spans? r)))
      (is (= ["Documentation" "Public"] (mapv :name (:category-path r))))
      (is (= "Public" (:bcast-name r)))
      (is (false? (:routable? r))))))
