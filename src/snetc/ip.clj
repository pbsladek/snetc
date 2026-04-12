(ns snetc.ip
  "Pure IPv4 bit-math: conversions, masks, addresses."
  (:require [clojure.string :as str]))

(defn ip->long
  "Returns the 32-bit unsigned long value of dotted-decimal IPv4 string ip.
  Throws ex-info if ip cannot be parsed, has wrong octet count, or any octet
  is outside 0–255."
  [ip]
  (try
    (let [parts (str/split ip #"\." -1)   ; -1 preserves trailing empty segments
          _ (when (not= 4 (count parts))
              (throw (Exception.)))
          [a b c d] (mapv #(Long/parseLong %) parts)
          _ (when-not (and (<= 0 a 255) (<= 0 b 255) (<= 0 c 255) (<= 0 d 255))
              (throw (Exception.)))]
      (+ (bit-shift-left a 24)
         (bit-shift-left b 16)
         (bit-shift-left c 8)
         d))
    (catch Exception _
      (throw (ex-info (str "Cannot parse IPv4 address: " ip) {:ip ip})))))

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
    ;; bit-and truncates to 32 bits: Clojure longs are 64-bit, so
    ;; (bit-shift-left -1 N) leaves the upper 32 bits set; masking
    ;; to 0xFFFFFFFF ensures the result fits in unsigned IPv4 range.
    (bit-and 0xFFFFFFFF (bit-shift-left -1 (- 32 prefix)))))

(defn mask->prefix
  "Returns the prefix length of a 32-bit contiguous subnet mask.
  Throws ex-info if mask is non-contiguous (e.g. 0xFF00FF00)."
  [mask]
  (let [prefix   (Long/bitCount mask)
        ;; Reconstruct what a valid contiguous mask looks like for this prefix
        ;; count, then verify the input matches — catches non-contiguous masks.
        expected (if (zero? prefix)
                   0
                   (bit-and 0xFFFFFFFF (bit-shift-left -1 (- 32 prefix))))]
    (when-not (= (bit-and mask 0xFFFFFFFF) expected)
      (throw (ex-info (str "Non-contiguous subnet mask: " mask) {:mask mask})))
    prefix))

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
