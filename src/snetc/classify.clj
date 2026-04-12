(ns snetc.classify
  "RFC-based IPv4 address classification."
  (:require [clojure.string :as str]
            [snetc.ip     :as ip]
            [snetc.subnet :as subnet]))

;; Sorted at construction time by prefix length descending so the most-specific
;; entry always wins when iterating — no manual ordering of the literal list required.
;; :start/:end/:prefix are pre-computed to avoid re-parsing on every classify call.
(def ^:private special-ranges
  (->> [{:cidr "255.255.255.255/32" :name "Limited Broadcast"         :rfc "RFC 919"}
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
        {:cidr "224.0.0.0/4"        :name "Multicast"                 :rfc "RFC 1112"}]
       (map (fn [{:keys [cidr] :as entry}]
              (let [[s e] (subnet/cidr->range cidr)
                    prefix (:prefix (subnet/parse-cidr cidr))]
                (assoc entry :start s :end e :prefix prefix))))
       (sort-by :prefix >)
       vec))

(defn- match-for
  "Returns {:name :rfc} for the first special range containing ip-n, or Public."
  [ip-n]
  (or (some (fn [{:keys [name rfc start end]}]
              (when (<= start ip-n end) {:name name :rfc rfc}))
            special-ranges)
      {:name "Public" :rfc ""}))

(defn classify
  "Returns {:input :name :rfc :routable? :spans?} for ip-or-cidr string.
  CIDRs are classified by network address. :spans? is true when the broadcast
  address falls in a different category; :bcast-name is added in that case."
  [input]
  (let [is-cidr? (str/includes? input "/")
        {:keys [ip-str prefix]} (subnet/parse-cidr (if is-cidr? input (str input "/32")))
        net      (ip/network-addr (ip/ip->long ip-str) prefix)
        bcast    (ip/broadcast-addr net prefix)
        net-m    (match-for net)
        bcast-m  (match-for bcast)
        ;; For plain IPs (treated as /32), net == bcast so spans? is naturally
        ;; false — no need to special-case is-cidr? here.
        spans?   (not= net-m bcast-m)]
    ;; :routable? reflects the address class, not per-prefix forwarding policy.
    ;; Directed broadcasts within public space (e.g. 1.2.3.255/24) are marked
    ;; routable because the address belongs to public space; RFC 2644 deprecates
    ;; forwarding them but that is a router policy, not an address classification.
    (merge {:input input :routable? (= "Public" (:name net-m)) :spans? spans?}
           net-m
           (when spans? {:bcast-name (:name bcast-m)}))))
