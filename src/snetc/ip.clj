(ns snetc.ip
  "Pure IPv4 bit-math: conversions, masks, addresses."
  (:require [clojure.string :as str]))

(defn ip->long
  "Parse a dotted-decimal IPv4 string into a 32-bit unsigned value held in a long."
  [ip]
  (let [[a b c d] (map #(Long/parseLong %) (str/split ip #"\."))]
    (+ (bit-shift-left a 24)
       (bit-shift-left b 16)
       (bit-shift-left c 8)
       d)))

(defn long->ip
  "Format a 32-bit unsigned long as a dotted-decimal IPv4 string."
  [n]
  (str/join "." [(bit-and (bit-shift-right n 24) 0xFF)
                 (bit-and (bit-shift-right n 16) 0xFF)
                 (bit-and (bit-shift-right n  8) 0xFF)
                 (bit-and n 0xFF)]))

(defn prefix->mask
  "Convert a CIDR prefix length (0–32) to a 32-bit unsigned mask in a long."
  [prefix]
  (if (zero? prefix)
    0
    (bit-and 0xFFFFFFFF (bit-shift-left -1 (- 32 prefix)))))

(defn mask->prefix
  "Convert a 32-bit mask to its CIDR prefix length."
  [mask]
  (Long/bitCount mask))

(defn wildcard-mask
  "Return the wildcard (host) mask for a given prefix length."
  [prefix]
  (bit-xor (prefix->mask prefix) 0xFFFFFFFF))

(defn network-addr
  "Apply the prefix mask to an IP long to get the network address."
  [ip prefix]
  (bit-and ip (prefix->mask prefix)))

(defn broadcast-addr
  "Compute the broadcast address for a network/prefix."
  [network prefix]
  (bit-or network (wildcard-mask prefix)))

(defn usable-hosts
  "Number of usable host addresses for a prefix length.
   /32 → 1 (the address itself), /31 → 2 (RFC 3021 point-to-point)."
  [prefix]
  (cond
    (= prefix 32) 1
    (= prefix 31) 2
    :else         (- (bit-shift-left 1 (- 32 prefix)) 2)))
