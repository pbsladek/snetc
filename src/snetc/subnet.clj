(ns snetc.subnet
  "CIDR parsing, validation, subnet calculations, and range utilities."
  (:require [clojure.string :as str]
            [snetc.ip :as ip]))

;; Matches each octet 0–255; leading zeros are rejected.
(def ^:private ip-re
  #"^(25[0-5]|2[0-4]\d|1\d\d|[1-9]\d|\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]\d|\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]\d|\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]\d|\d)$")

(defn valid-ip?
  "Returns true if s is a valid dotted-decimal IPv4 address with no leading zeros."
  [s]
  (boolean (re-matches ip-re s)))

(defn valid-prefix?
  "Returns true if p is an integer in [0, 32]."
  [p]
  (<= 0 p 32))

(defn parse-cidr
  "Returns {:ip-str :prefix} for CIDR string. Throws ex-info on invalid input."
  [cidr]
  (let [[ip-str prefix-str] (str/split cidr #"/" 2)]
    (when-not (valid-ip? ip-str)
      (throw (ex-info (str "Invalid IP address: " ip-str) {:cidr cidr})))
    (when (nil? prefix-str)
      (throw (ex-info (str "Missing prefix length: " cidr) {:cidr cidr})))
    (let [prefix (try (Integer/parseInt prefix-str)
                      (catch Exception _
                        (throw (ex-info (str "Invalid prefix: " prefix-str) {:cidr cidr}))))]
      (when-not (valid-prefix? prefix)
        (throw (ex-info (str "Prefix must be 0–32, got: " prefix) {:cidr cidr})))
      {:ip-str ip-str :prefix prefix})))

(defn subnet-info
  "Returns a map of subnet details for cidr. Host bits in the input are masked off."
  [cidr]
  (let [{:keys [ip-str prefix]} (parse-cidr cidr)
        ip-n  (ip/ip->long ip-str)
        net   (ip/network-addr ip-n prefix)
        bcast (ip/broadcast-addr net prefix)
        mask  (ip/prefix->mask prefix)
        wild  (ip/wildcard-mask prefix)
        hosts (ip/usable-hosts prefix)]
    {:network    (ip/long->ip net)
     :broadcast  (ip/long->ip bcast)
     :first-host (if (>= prefix 31) (ip/long->ip net)   (ip/long->ip (inc net)))
     :last-host  (if (>= prefix 31) (ip/long->ip bcast) (ip/long->ip (dec bcast)))
     :hosts      hosts
     :mask       (ip/long->ip mask)
     :wildcard   (ip/long->ip wild)
     :prefix     prefix
     :cidr       (str (ip/long->ip net) "/" prefix)}))

(defn cidr->range
  "Returns [start end] as inclusive longs for cidr."
  [cidr]
  (let [{:keys [ip-str prefix]} (parse-cidr cidr)
        net (ip/network-addr (ip/ip->long ip-str) prefix)]
    [net (ip/broadcast-addr net prefix)]))

(defn range->cidrs
  "Returns the minimal list of CIDR strings covering the inclusive [start end] range."
  [start end]
  (when (<= start end)
    (let [tz     (if (zero? start) 32 (Long/numberOfTrailingZeros start))
          size   (loop [s (bit-shift-left 1 (min 32 tz))]
                   (if (<= (dec (+ start s)) end)
                     s
                     (recur (bit-shift-right s 1))))
          prefix (- 32 (Long/numberOfTrailingZeros size))
          blkend (dec (+ start size))]
      (cons (str (ip/long->ip start) "/" prefix)
            (lazy-seq (range->cidrs (inc blkend) end))))))

(defn ip-in-cidr?
  "Returns true if ip falls within cidr."
  [ip cidr]
  (let [{:keys [ip-str prefix]} (parse-cidr cidr)
        net (ip/network-addr (ip/ip->long ip-str) prefix)]
    (= net (ip/network-addr (ip/ip->long ip) prefix))))

(defn split-subnets
  "Returns all /new-prefix subnets within cidr. Throws if new-prefix < base prefix."
  [cidr new-prefix]
  (let [{:keys [ip-str prefix]} (parse-cidr cidr)]
    (when (< new-prefix prefix)
      (throw (ex-info (str "Split prefix /" new-prefix
                           " must be ≥ base prefix /" prefix)
                      {:cidr cidr :new-prefix new-prefix})))
    (let [net  (ip/network-addr (ip/ip->long ip-str) prefix)
          n    (bit-shift-left 1 (- new-prefix prefix))
          size (bit-shift-left 1 (- 32 new-prefix))]
      (for [i (range n)]
        (subnet-info (str (ip/long->ip (+ net (* i size))) "/" new-prefix))))))

(defn subnet-tree
  "Returns a tree splitting cidr down to max-prefix. Each node is {:info :children}."
  [cidr max-prefix]
  (let [info (subnet-info cidr)]
    {:info     info
     :children (when (< (:prefix info) max-prefix)
                 (mapv #(subnet-tree (:cidr %) max-prefix)
                       (split-subnets cidr (inc (:prefix info)))))}))
