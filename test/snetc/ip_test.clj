(ns snetc.ip-test
  (:require [clojure.test :refer [deftest is]]
            [snetc.ip :refer [ip->long long->ip
                              prefix->mask mask->prefix
                              wildcard-mask network-addr broadcast-addr
                              usable-hosts]]))

;;; ── ip->long ─────────────────────────────────────────────────────────────────

(deftest ip->long-test
  (is (= 0          (ip->long "0.0.0.0")))
  (is (= 4294967295 (ip->long "255.255.255.255")))
  (is (= 3232235520 (ip->long "192.168.0.0")))
  (is (= 3232236543 (ip->long "192.168.3.255")))
  (is (= 167772161  (ip->long "10.0.0.1"))))

;;; ── long->ip ─────────────────────────────────────────────────────────────────

(deftest long->ip-test
  (is (= "0.0.0.0"         (long->ip 0)))
  (is (= "255.255.255.255" (long->ip 4294967295)))
  (is (= "192.168.0.0"     (long->ip 3232235520)))
  (is (= "10.0.0.1"        (long->ip 167772161))))

(deftest ip-round-trip-test
  (doseq [ip ["0.0.0.0" "255.255.255.255" "192.168.1.100" "10.0.0.1" "172.16.0.0"]]
    (is (= ip (long->ip (ip->long ip))))))

;;; ── prefix->mask ─────────────────────────────────────────────────────────────

(deftest prefix->mask-test
  (is (= 0x00000000 (prefix->mask 0)))
  (is (= 0xFF000000 (prefix->mask 8)))
  (is (= 0xFFFF0000 (prefix->mask 16)))
  (is (= 0xFFFFFC00 (prefix->mask 22)))
  (is (= 0xFFFFFF00 (prefix->mask 24)))
  (is (= 0xFFFFFFFE (prefix->mask 31)))
  (is (= 0xFFFFFFFF (prefix->mask 32))))

;;; ── mask->prefix ─────────────────────────────────────────────────────────────

(deftest mask->prefix-test
  (is (= 0  (mask->prefix 0x00000000)))
  (is (= 8  (mask->prefix 0xFF000000)))
  (is (= 16 (mask->prefix 0xFFFF0000)))
  (is (= 22 (mask->prefix 0xFFFFFC00)))
  (is (= 24 (mask->prefix 0xFFFFFF00)))
  (is (= 31 (mask->prefix 0xFFFFFFFE)))
  (is (= 32 (mask->prefix 0xFFFFFFFF))))

(deftest prefix-mask-round-trip-test
  (doseq [p (range 0 33)]
    (is (= p (mask->prefix (prefix->mask p))))))

;;; ── wildcard-mask ────────────────────────────────────────────────────────────

(deftest wildcard-mask-test
  (is (= 0xFFFFFFFF (wildcard-mask 0)))
  (is (= 0x00FFFFFF (wildcard-mask 8)))
  (is (= 0x000003FF (wildcard-mask 22)))
  (is (= 0x000000FF (wildcard-mask 24)))
  (is (= 0x00000001 (wildcard-mask 31)))
  (is (= 0x00000000 (wildcard-mask 32))))

;;; ── network-addr ─────────────────────────────────────────────────────────────

(deftest network-addr-test
  (is (= (ip->long "192.168.0.0") (network-addr (ip->long "192.168.1.5")   22)))
  (is (= (ip->long "192.168.0.0") (network-addr (ip->long "192.168.0.0")   22)))
  (is (= (ip->long "192.168.0.0") (network-addr (ip->long "192.168.3.255") 22)))
  (is (= (ip->long "10.0.0.0")    (network-addr (ip->long "10.0.0.255")    8)))
  (is (= (ip->long "192.168.1.0") (network-addr (ip->long "192.168.1.42")  24)))
  ;; /32 preserves the host address exactly
  (is (= (ip->long "1.2.3.4")     (network-addr (ip->long "1.2.3.4")       32)))
  ;; /0 always gives 0.0.0.0
  (is (= 0                        (network-addr (ip->long "255.255.255.255") 0))))

;;; ── broadcast-addr ───────────────────────────────────────────────────────────

(deftest broadcast-addr-test
  (is (= (ip->long "192.168.3.255")   (broadcast-addr (ip->long "192.168.0.0") 22)))
  (is (= (ip->long "10.255.255.255")  (broadcast-addr (ip->long "10.0.0.0")    8)))
  (is (= (ip->long "192.168.1.255")   (broadcast-addr (ip->long "192.168.1.0") 24)))
  ;; /32 broadcast == the address itself
  (is (= (ip->long "1.2.3.4")         (broadcast-addr (ip->long "1.2.3.4")     32)))
  ;; /0 broadcast == 255.255.255.255
  (is (= (ip->long "255.255.255.255") (broadcast-addr (ip->long "0.0.0.0")     0))))

;;; ── usable-hosts ─────────────────────────────────────────────────────────────

(deftest usable-hosts-test
  (is (= 1          (usable-hosts 32)))
  (is (= 2          (usable-hosts 31)))
  (is (= 2          (usable-hosts 30)))
  (is (= 6          (usable-hosts 29)))
  (is (= 254        (usable-hosts 24)))
  (is (= 1022       (usable-hosts 22)))
  (is (= 65534      (usable-hosts 16)))
  (is (= 4294967294 (usable-hosts 0))))
