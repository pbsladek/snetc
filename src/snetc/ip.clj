(ns snetc.ip
  "Pure IPv4 bit-math: conversions, masks, addresses."
  (:require [clojure.string :as str]))

(defn ip->long
  "Returns the 32-bit unsigned long value of dotted-decimal IPv4 string ip."
  [ip]
  (let [[a b c d] (map #(Long/parseLong %) (str/split ip #"\."))]
    (+ (bit-shift-left a 24)
       (bit-shift-left b 16)
       (bit-shift-left c 8)
       d)))

(defn long->ip
  "Returns the dotted-decimal string for 32-bit unsigned long n."
  [n]
  (str/join "." [(bit-and (bit-shift-right n 24) 0xFF)
                 (bit-and (bit-shift-right n 16) 0xFF)
                 (bit-and (bit-shift-right n  8) 0xFF)
                 (bit-and n 0xFF)]))

(defn prefix->mask
  "Returns the 32-bit subnet mask for prefix length (0–32)."
  [prefix]
  (if (zero? prefix)
    0
    (bit-and 0xFFFFFFFF (bit-shift-left -1 (- 32 prefix)))))

(defn mask->prefix
  "Returns the prefix length of a 32-bit contiguous subnet mask."
  [mask]
  (Long/bitCount mask))

(defn wildcard-mask
  "Returns the wildcard (inverse) mask for prefix."
  [prefix]
  (bit-xor (prefix->mask prefix) 0xFFFFFFFF))

(defn network-addr
  "Returns ip with host bits zeroed to prefix length."
  [ip prefix]
  (bit-and ip (prefix->mask prefix)))

(defn broadcast-addr
  "Returns the broadcast address for network at prefix length."
  [network prefix]
  (bit-or network (wildcard-mask prefix)))

(defn usable-hosts
  "Returns the number of usable host addresses for prefix.
  /31 returns 2 (RFC 3021 point-to-point); /32 returns 1."
  [prefix]
  (cond
    (= prefix 32) 1
    (= prefix 31) 2
    :else         (- (bit-shift-left 1 (- 32 prefix)) 2)))
