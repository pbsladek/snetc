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

(defn- overlapping? [start end range-start range-end]
  (and (<= range-start end) (>= range-end start)))

(defn- category-boundaries
  "Returns sorted segment boundaries where classification can change in [start,end]."
  [start end]
  (let [end-excl (inc end)]
    (->> special-ranges
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
  [start end]
  (->> (category-boundaries start end)
       (partition 2 1)
       (map (fn [[segment-start _]] (match-for segment-start)))
       collapse-adjacent))

(defn classify
  "Returns {:input :name :rfc :routable? :spans?} for ip-or-cidr string.
  :name/:rfc describe the network address. CIDRs also include :category-path;
  :spans? is true when any segment in the CIDR falls in a different category."
  [input]
  (let [is-cidr? (str/includes? input "/")
        {:keys [ip-str prefix]} (subnet/parse-cidr (if is-cidr? input (str input "/32")))
        net      (ip/network-addr (ip/ip->long ip-str) prefix)
        bcast    (ip/broadcast-addr net prefix)
        path     (category-path net bcast)
        net-m    (first path)
        spans?   (> (count path) 1)]
    ;; :routable? reflects address classification, not per-prefix forwarding
    ;; policy. A CIDR is routable only when every segment is public.
    (merge {:input input
            :routable? (every? #(= "Public" (:name %)) path)
            :spans? spans?
            :category-path path}
           net-m
           (when spans? {:bcast-name (str/join " → " (map :name (rest path)))}))))
