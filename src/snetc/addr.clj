(ns snetc.addr
  "Family-aware IPv4/IPv6 address parsing and CIDR math."
  (:require [clojure.string :as str]
            [snetc.ip :as ip]))

;; Keep IPv4 parsing strict and compatible with snetc.subnet: exactly four
;; dotted-decimal octets, no leading zeros, each octet 0-255.
(def ^:private ipv4-re
  #"^(25[0-5]|2[0-4]\d|1\d\d|[1-9]\d|\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]\d|\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]\d|\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]\d|\d)$")

(def ^:private prefix-re #"0|[1-9]\d{0,2}")
(def ^:private family-bits {:ipv4 32 :ipv6 128})
(def ^:private hex-re #"(?i)^[0-9a-f]{1,4}$")

(defn- pow2 [bits]
  (bigint (.shiftLeft java.math.BigInteger/ONE bits)))

(defn- family-label [family]
  (case family
    :ipv4 "IPv4"
    :ipv6 "IPv6"))

(defn valid-prefix?
  "Returns true if p is valid for address family."
  [family p]
  (boolean
   (when-let [bits (family-bits family)]
     (and (integer? p) (<= 0 p bits)))))

(defn- bits-for [family]
  (or (family-bits family)
      (throw (ex-info (str "Unknown address family: " family) {:family family}))))

(defn- max-address [family]
  (dec (pow2 (bits-for family))))

(defn address-count
  "Returns the number of addresses in a family/prefix block."
  [family prefix]
  (let [bits (bits-for family)]
    (when-not (valid-prefix? family prefix)
      (throw (ex-info (str "Invalid " (family-label family) " prefix: " prefix)
                      {:family family :prefix prefix})))
    (pow2 (- bits prefix))))

(defn network-addr
  "Returns addr with host bits zeroed for family/prefix."
  [family addr prefix]
  (let [size (address-count family prefix)]
    (* (quot (bigint addr) size) size)))

(defn last-addr
  "Returns the inclusive last address for network at family/prefix."
  [family network prefix]
  (+ (bigint network) (dec (address-count family prefix))))

(defn- parse-prefix [prefix-str family cidr]
  (when-not (re-matches prefix-re prefix-str)
    (throw (ex-info (str "Invalid prefix: " prefix-str) {:cidr cidr})))
  (let [prefix (Integer/parseInt prefix-str)
        bits   (family-bits family)]
    (when-not (valid-prefix? family prefix)
      (throw (ex-info (str "Prefix must be 0-" bits ", got: " prefix) {:cidr cidr})))
    prefix))

(defn- parse-hextet [s]
  (when-not (re-matches hex-re s)
    (throw (ex-info (str "Invalid IPv6 hextet: " s) {:hextet s})))
  (Integer/parseInt s 16))

(defn- embedded-ipv4->hextets [s]
  (let [n (ip/ip->long s)]
    [(bit-and (bit-shift-right n 16) 0xFFFF)
     (bit-and n 0xFFFF)]))

(defn- normalize-embedded-ipv4 [s]
  (if-not (str/includes? s ".")
    s
    (let [idx (str/last-index-of s ":")]
      (when (nil? idx)
        (throw (ex-info (str "Invalid IPv6 address: " s) {:ip s})))
      (let [prefix  (subs s 0 (inc idx))
            suffix  (subs s (inc idx))
            hextets (try (embedded-ipv4->hextets suffix)
                         (catch Exception _
                           (throw (ex-info (str "Invalid embedded IPv4 address: " suffix) {:ip s}))))]
        (str prefix (format "%x:%x" (first hextets) (second hextets)))))))

(defn- split-hextets [s]
  (if (str/blank? s)
    []
    (let [parts (str/split s #":" -1)]
      (when (some str/blank? parts)
        (throw (ex-info (str "Invalid IPv6 address: " s) {:ip s})))
      (mapv parse-hextet parts))))

(defn- parse-ipv6-hextets [raw]
  (when (or (not (str/includes? raw ":"))
            (str/includes? raw "%")
            (not (re-matches #"(?i)^[0-9a-f:.]+$" raw)))
    (throw (ex-info (str "Invalid IPv6 address: " raw) {:ip raw})))
  (let [s     (normalize-embedded-ipv4 raw)
        parts (str/split s #"::" -1)]
    (when (> (count parts) 2)
      (throw (ex-info (str "Invalid IPv6 address: " raw) {:ip raw})))
    (if (= 2 (count parts))
      (let [[left-str right-str] parts
            left     (split-hextets left-str)
            right    (split-hextets right-str)
            missing  (- 8 (count left) (count right))]
        (when (< missing 1)
          (throw (ex-info (str "Invalid IPv6 address: " raw) {:ip raw})))
        (vec (concat left (repeat missing 0) right)))
      (let [hextets (split-hextets s)]
        (when-not (= 8 (count hextets))
          (throw (ex-info (str "Invalid IPv6 address: " raw) {:ip raw})))
        hextets))))

(defn- ipv6-hextets->number [hextets]
  (reduce (fn [acc hextet] (+ (* acc 65536N) hextet)) 0N hextets))

(defn- number->ipv6-hextets [n]
  (mapv (fn [idx]
          (let [shift (* 16 (- 7 idx))]
            (long (mod (quot n (pow2 shift)) 65536N))))
        (range 8)))

(defn- zero-runs [hextets]
  (loop [idx 0 runs []]
    (cond
      (= idx 8) runs

      (zero? (nth hextets idx))
      (let [end (loop [j idx]
                  (if (and (< j 8) (zero? (nth hextets j)))
                    (recur (inc j))
                    j))]
        (recur end (conj runs [idx (- end idx)])))

      :else
      (recur (inc idx) runs))))

(defn- longest-zero-run [hextets]
  (let [[_ len :as run] (first (sort-by (fn [[start len]] [(- len) start])
                                        (zero-runs hextets)))]
    (when (and run (>= len 2)) run)))

(defn- number->ipv6 [n]
  (let [hextets (number->ipv6-hextets n)
        fmt     #(Long/toHexString (long %))]
    (if-let [[start len] (longest-zero-run hextets)]
      (if (= len 8)
        "::"
        (str (str/join ":" (map fmt (subvec hextets 0 start)))
             "::"
             (str/join ":" (map fmt (subvec hextets (+ start len))))))
      (str/join ":" (map fmt hextets)))))

(defn- number->text [family n]
  (case family
    :ipv4 (ip/long->ip (long n))
    :ipv6 (number->ipv6 n)))

(defn address->text
  "Returns canonical text for numeric address n in family."
  [family n]
  (let [n (bigint n)]
    (when (or (< n 0N) (> n (max-address family)))
      (throw (ex-info (str "Address falls outside " (family-label family) " address space")
                      {:family family :addr n})))
    (number->text family n)))

(defn parse-ip
  "Parses an IPv4 or IPv6 literal into a family-aware address map."
  [s]
  (cond
    (not (string? s))
    (throw (ex-info (str "Invalid IP address: " s) {:ip s}))

    (re-matches ipv4-re s)
    {:family :ipv4 :bits 32 :addr (bigint (ip/ip->long s)) :text s}

    (str/includes? s ":")
    (try
      (let [addr (ipv6-hextets->number (parse-ipv6-hextets s))]
        {:family :ipv6 :bits 128 :addr addr :text (number->ipv6 addr)})
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception _
        (throw (ex-info (str "Invalid IPv6 address: " s) {:ip s}))))

    :else
    (throw (ex-info (str "Invalid IP address: " s) {:ip s}))))

(defn parse-cidr
  "Parses an IPv4 or IPv6 CIDR and normalizes host bits in :cidr."
  [cidr]
  (let [parts (str/split cidr #"/" -1)]
    (when-not (= 2 (count parts))
      (throw (ex-info (str "Missing prefix length: " cidr) {:cidr cidr})))
    (let [[ip-str prefix-str] parts
          parsed              (parse-ip ip-str)
          family              (:family parsed)
          bits                (:bits parsed)
          prefix              (parse-prefix prefix-str family cidr)
          network             (network-addr family (:addr parsed) prefix)
          last                (last-addr family network prefix)]
      {:family family
       :bits    bits
       :ip-str  (:text parsed)
       :addr    (:addr parsed)
       :prefix  prefix
       :network network
       :last    last
       :cidr    (str (number->text family network) "/" prefix)})))

(defn subnet-info
  "Returns subnet details for an IPv4 or IPv6 CIDR."
  [cidr]
  (let [{:keys [family bits prefix network last cidr]} (parse-cidr cidr)
        addresses (pow2 (- bits prefix))]
    (case family
      :ipv4
      (let [net   (long network)
            bcast (long last)
            mask  (ip/prefix->mask prefix)
            wild  (ip/wildcard-mask prefix)]
        (cond-> {:family        :ipv4
                 :network       (ip/long->ip net)
                 :first-address (ip/long->ip net)
                 :last-address  (ip/long->ip bcast)
                 :addresses     addresses
                 :first-host    (if (>= prefix 31) (ip/long->ip net) (ip/long->ip (inc net)))
                 :last-host     (if (>= prefix 31) (ip/long->ip bcast) (ip/long->ip (dec bcast)))
                 :hosts         (ip/usable-hosts prefix)
                 :mask          (ip/long->ip mask)
                 :wildcard      (ip/long->ip wild)
                 :prefix        prefix
                 :cidr          cidr}
          (< prefix 32) (assoc :broadcast (ip/long->ip bcast))))

      :ipv6
      {:family        :ipv6
       :network       (number->ipv6 network)
       :first-address (number->ipv6 network)
       :last-address  (number->ipv6 last)
       :addresses     addresses
       :prefix        prefix
       :cidr          cidr})))

(defn cidr->range
  "Returns {:family :start :end} for cidr, with an inclusive bigint range."
  [cidr]
  (let [{:keys [family network last]} (parse-cidr cidr)]
    {:family family :start network :end last}))

(defn- largest-aligned-size [start max-size]
  (loop [size 1N]
    (let [next-size (* size 2N)]
      (if (and (<= next-size max-size)
               (zero? (mod start next-size)))
        (recur next-size)
        size))))

(defn- power-of-two-exponent [n]
  (loop [x (bigint n) exp 0]
    (if (= 1N x)
      exp
      (recur (quot x 2N) (inc exp)))))

(defn range->cidrs
  "Returns a vector of minimal CIDRs covering inclusive [start end] for family.
  Returns nil when start is greater than end."
  [family start end]
  (let [bits   (bits-for family)
        start  (bigint start)
        end    (bigint end)
        max-n  (max-address family)
        space  (pow2 bits)]
    (when (or (< start 0N) (< end 0N) (> start max-n) (> end max-n))
      (throw (ex-info (str "Range falls outside " (family-label family) " address space")
                      {:family family :start start :end end})))
    (when (<= start end)
      (loop [pos start
             out []]
        (if (> pos end)
          out
          (let [remaining (inc (- end pos))
                aligned   (largest-aligned-size pos space)
                size      (loop [size aligned]
                            (if (<= size remaining)
                              size
                              (recur (quot size 2N))))
                prefix    (- bits (power-of-two-exponent size))]
            (recur (+ pos size)
                   (conj out (str (number->text family pos) "/" prefix)))))))))

(defn ip-in-cidr?
  "Returns true if ip falls within cidr. Mixed address families are invalid."
  [ip cidr]
  (let [parsed-ip   (parse-ip ip)
        parsed-cidr (parse-cidr cidr)]
    (when (not= (:family parsed-ip) (:family parsed-cidr))
      (throw (ex-info (str "Mixed address families: "
                           (family-label (:family parsed-ip)) " address with "
                           (family-label (:family parsed-cidr)) " CIDR")
                      {:ip ip :cidr cidr})))
    (<= (:network parsed-cidr) (:addr parsed-ip) (:last parsed-cidr))))
