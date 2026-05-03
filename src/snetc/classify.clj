(ns snetc.classify
  "RFC/IANA-based IPv4 and IPv6 address classification."
  (:require [clojure.string :as str]
            [snetc.addr   :as addr]))

;; Sorted at construction time by prefix length descending so the most-specific
;; entry always wins when iterating — no manual ordering of the literal list required.
;; :start/:end/:prefix are pre-computed to avoid re-parsing on every classify call.
(def ^:private ipv4-special-ranges
  [{:cidr "255.255.255.255/32" :name "Limited Broadcast"         :rfc "RFC 919"}
   {:cidr "192.0.0.0/24"       :name "IETF Protocol Assignments" :rfc "RFC 6890"}
   {:cidr "192.0.2.0/24"       :name "Documentation TEST-NET-1"  :rfc "RFC 5737"}
   {:cidr "198.51.100.0/24"    :name "Documentation TEST-NET-2"  :rfc "RFC 5737"}
   {:cidr "203.0.113.0/24"     :name "Documentation TEST-NET-3"  :rfc "RFC 5737"}
   {:cidr "169.254.0.0/16"     :name "Link-Local"                :rfc "RFC 3927"}
   {:cidr "192.168.0.0/16"     :name "Private"                   :rfc "RFC 1918"}
   {:cidr "198.18.0.0/15"      :name "Benchmarking"              :rfc "RFC 2544"}
   {:cidr "172.16.0.0/12"      :name "Private"                   :rfc "RFC 1918"}
   {:cidr "100.64.0.0/10"      :name "Shared Address Space"      :rfc "RFC 6598"}
   {:cidr "10.0.0.0/8"         :name "Private"                   :rfc "RFC 1918"}
   {:cidr "127.0.0.0/8"        :name "Loopback"                  :rfc "RFC 1122"}
   {:cidr "0.0.0.0/8"          :name "This Network"              :rfc "RFC 1122"}
   {:cidr "240.0.0.0/4"        :name "Reserved"                  :rfc "RFC 1112"}
   {:cidr "224.0.0.0/4"        :name "Multicast"                 :rfc "RFC 1112"}])

;; IPv6 source references:
;; - IANA IPv6 Special-Purpose Address Space:
;;   https://www.iana.org/assignments/iana-ipv6-special-registry
;; - IANA IPv6 Address Space:
;;   https://www.iana.org/assignments/ipv6-address-space
;; - IANA IPv6 Multicast Address Space:
;;   https://www.iana.org/assignments/ipv6-multicast-addresses
(def ^:private ipv6-special-ranges
  [{:cidr "::1/128"          :name "Loopback"                  :rfc "RFC 4291"}
   {:cidr "::/128"           :name "Unspecified"               :rfc "RFC 4291"}
   {:cidr "::ffff:0:0/96"    :name "IPv4-mapped"               :rfc "RFC 4291"}
   {:cidr "64:ff9b::/96"     :name "IPv4-IPv6 Translation"     :rfc "RFC 6052"}
   {:cidr "64:ff9b:1::/48"   :name "IPv4-IPv6 Translation"     :rfc "RFC 8215"}
   {:cidr "100::/64"         :name "Discard-Only"              :rfc "RFC 6666"}
   {:cidr "100:0:0:1::/64"   :name "Dummy IPv6 Prefix"         :rfc "RFC 9780"}
   {:cidr "2001:1::1/128"    :name "PCP Anycast"               :rfc "RFC 7723"}
   {:cidr "2001:1::2/128"    :name "TURN Anycast"              :rfc "RFC 8155"}
   {:cidr "2001:1::3/128"    :name "DNS-SD SRP Anycast"        :rfc "RFC 9665"}
   {:cidr "2001:2::/48"      :name "Benchmarking"              :rfc "RFC 5180"}
   {:cidr "2001:3::/32"      :name "AMT"                       :rfc "RFC 7450"}
   {:cidr "2001:4:112::/48"  :name "AS112-v6"                  :rfc "RFC 7535"}
   {:cidr "2001:10::/28"     :name "Deprecated ORCHID"         :rfc "RFC 4843"}
   {:cidr "2001:20::/28"     :name "ORCHIDv2"                  :rfc "RFC 7343"}
   {:cidr "2001:30::/28"     :name "Drone Remote ID DETs"      :rfc "RFC 9374"}
   {:cidr "2001:db8::/32"    :name "Documentation"             :rfc "RFC 3849"}
   {:cidr "3fff::/20"        :name "Documentation"             :rfc "RFC 9637"}
   {:cidr "2001::/32"        :name "Teredo"                    :rfc "RFC 4380/RFC 8190"}
   {:cidr "2001::/23"        :name "IETF Protocol Assignments" :rfc "RFC 2928"}
   {:cidr "2002::/16"        :name "6to4"                      :rfc "RFC 3056"}
   {:cidr "2620:4f:8000::/48" :name "Direct Delegation AS112"   :rfc "RFC 7534"}
   {:cidr "5f00::/16"        :name "Segment Routing SIDs"      :rfc "RFC 9602"}
   {:cidr "fc00::/7"         :name "Unique Local"              :rfc "RFC 4193"}
   {:cidr "fe80::/10"        :name "Link-Local"                :rfc "RFC 4291"}
   {:cidr "ff00::/8"         :name "Multicast"                 :rfc "RFC 4291/RFC 3307"}
   {:cidr "::/8"             :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "100::/8"          :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "200::/7"          :name "Reserved by IETF"          :rfc "RFC 4048"}
   {:cidr "400::/6"          :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "800::/5"          :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "1000::/4"         :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "4000::/3"         :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "6000::/3"         :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "8000::/3"         :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "a000::/3"         :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "c000::/3"         :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "e000::/4"         :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "f000::/5"         :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "f800::/6"         :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "fe00::/9"         :name "Reserved by IETF"          :rfc "RFC 3513/RFC 4291"}
   {:cidr "fec0::/10"        :name "Reserved by IETF"          :rfc "RFC 3879"}])

(def ^:private special-ranges
  (->> (concat ipv4-special-ranges ipv6-special-ranges)
       (map (fn [{:keys [cidr] :as entry}]
              (let [{:keys [family start end]} (addr/cidr->range cidr)
                    prefix (:prefix (addr/parse-cidr cidr))]
                (assoc entry :family family :start start :end end :prefix prefix))))
       (sort-by :prefix >)
       vec))

(defn- match-for
  "Returns {:name :rfc} for the first special range containing ip-n, or Public."
  [family ip-n]
  (or (some (fn [{:keys [name rfc start end]}]
              (when (<= start ip-n end) {:name name :rfc rfc}))
            (filter #(= family (:family %)) special-ranges))
      {:name "Public" :rfc ""}))

(defn- overlapping? [start end range-start range-end]
  (and (<= range-start end) (>= range-end start)))

(defn- category-boundaries
  "Returns sorted segment boundaries where classification can change in [start,end]."
  [family start end]
  (let [end-excl (inc end)]
    (->> special-ranges
         (filter #(= family (:family %)))
         (filter (fn [{range-start :start range-end :end}]
                   (overlapping? start end range-start range-end)))
         (mapcat (fn [{range-start :start range-end :end}]
                   [(max start range-start)
                    (min end-excl (inc range-end))]))
         (concat [start end-excl])
         distinct
         sort
         vec)))

(defn- collapse-adjacent [matches]
  (reduce (fn [acc match]
            (if (= match (peek acc))
              acc
              (conj acc match)))
          []
          matches))

(defn- category-path
  "Returns the ordered classification path for every segment in [start,end]."
  [family start end]
  (->> (category-boundaries family start end)
       (partition 2 1)
       (map (fn [[segment-start _]] (match-for family segment-start)))
       collapse-adjacent))

(defn- parse-input [input]
  (if (str/includes? input "/")
    (addr/parse-cidr input)
    (let [{:keys [bits text]} (addr/parse-ip input)]
      (addr/parse-cidr (str text "/" bits)))))

(defn classify
  "Returns {:input :name :rfc :routable? :spans?} for ip-or-cidr string.
  :name/:rfc describe the network address. CIDRs also include :category-path;
  :spans? is true when any segment in the CIDR falls in a different category."
  [input]
  (let [{:keys [family network last]} (parse-input input)
        path     (category-path family network last)
        net-m    (first path)
        spans?   (> (count path) 1)]
    ;; :routable? reflects address classification, not per-prefix forwarding
    ;; policy. A CIDR is routable only when every segment is public.
    (merge {:input input
            :family family
            :routable? (every? #(= "Public" (:name %)) path)
            :spans? spans?
            :category-path path}
           net-m
           (when spans? {:bcast-name (str/join " → " (map :name (rest path)))}))))
