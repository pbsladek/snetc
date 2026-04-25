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
    ;; Reject leading zeros ("024"), whitespace (" 24"), and non-numeric input.
    ;; The IP regex already rejects leading zeros in octets; apply the same
    ;; strictness to the prefix.
    (when-not (re-matches #"0|[1-9]\d?" prefix-str)
      (throw (ex-info (str "Invalid prefix: " prefix-str) {:cidr cidr})))
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
    (cond-> {:network    (ip/long->ip net)
             :first-host (if (>= prefix 31) (ip/long->ip net)   (ip/long->ip (inc net)))
             :last-host  (if (>= prefix 31) (ip/long->ip bcast) (ip/long->ip (dec bcast)))
             :hosts      hosts
             :mask       (ip/long->ip mask)
             :wildcard   (ip/long->ip wild)
             :prefix     prefix
             :cidr       (str (ip/long->ip net) "/" prefix)}
      ;; /32 host routes have no distinct broadcast address — the concept is
      ;; undefined for a single-host route. Omit :broadcast to avoid confusion.
      (< prefix 32) (assoc :broadcast (ip/long->ip bcast)))))

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
  (when-not (valid-prefix? new-prefix)
    (throw (ex-info (str "New prefix must be 0–32, got: " new-prefix)
                    {:new-prefix new-prefix})))
  (let [{:keys [ip-str prefix]} (parse-cidr cidr)]
    (when (< new-prefix prefix)
      (throw (ex-info (str "Split prefix /" new-prefix
                           " must be ≥ base prefix /" prefix)
                      {:cidr cidr :new-prefix new-prefix})))
    (let [net  (ip/network-addr (ip/ip->long ip-str) prefix)
          n    (bit-shift-left 1 (- new-prefix prefix))
          size (bit-shift-left 1 (- 32 new-prefix))]
      (when (> n 65536)
        (throw (ex-info (str "Split would produce " n " subnets; limit is 65536. "
                             "Use --tree for hierarchical splitting.")
                        {:cidr cidr :new-prefix new-prefix :count n})))
      (for [i (range n)]
        (subnet-info (str (ip/long->ip (+ net (* i size))) "/" new-prefix))))))

(defn adjacent-cidr
  "Returns the CIDR n blocks after (positive n) or before (negative n) cidr.
  Throws ex-info if the result would fall outside 0.0.0.0–255.255.255.255."
  [cidr n]
  (let [{:keys [ip-str prefix]} (parse-cidr cidr)
        net   (ip/network-addr (ip/ip->long ip-str) prefix)
        size  (bit-shift-left 1 (- 32 prefix))
        shift (* n size)
        new-net (+ net shift)]
    (when (or (< new-net 0) (> new-net 0xFFFFFFFF))
      (throw (ex-info (str "Adjacent block falls outside valid IPv4 range") {:cidr cidr :n n})))
    (str (ip/long->ip new-net) "/" prefix)))

(def ^:private max-tree-leaves 65536)

(defn- tree-leaf-count [prefix max-prefix]
  (.shiftLeft (biginteger 1) (- max-prefix prefix)))

(defn- guard-tree-size! [cidr prefix max-prefix]
  (when-not (valid-prefix? max-prefix)
    (throw (ex-info (str "Max prefix must be 0–32, got: " max-prefix)
                    {:cidr cidr :max-prefix max-prefix})))
  (when (< max-prefix prefix)
    (throw (ex-info (str "Max prefix /" max-prefix
                         " must be ≥ base prefix /" prefix)
                    {:cidr cidr :max-prefix max-prefix})))
  (let [leaves (tree-leaf-count prefix max-prefix)
        nodes  (dec (* 2N leaves))]
    (when (> leaves max-tree-leaves)
      (throw (ex-info (str "Subnet tree would produce " leaves
                           " leaf subnet(s) and " nodes
                           " total node(s); limit is " max-tree-leaves
                           " leaf subnet(s).")
                      {:cidr cidr
                       :max-prefix max-prefix
                       :leaves leaves
                       :nodes nodes
                       :limit max-tree-leaves})))))

(defn subnet-tree
  "Returns a tree splitting cidr down to max-prefix. Each node is {:info :children}."
  [cidr max-prefix]
  (let [info (subnet-info cidr)]
    (guard-tree-size! cidr (:prefix info) max-prefix)
    {:info     info
     :children (when (< (:prefix info) max-prefix)
                 (mapv #(subnet-tree (:cidr %) max-prefix)
                       (split-subnets cidr (inc (:prefix info)))))}))
