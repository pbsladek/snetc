(ns snetc.classify
  "RFC-based IPv4 address classification."
  (:require [clojure.string :as str]
            [snetc.ip     :as ip]
            [snetc.subnet :as subnet]))

;;; Longest-prefix-first so the most-specific entry wins on containment
;;; (e.g. 255.255.255.255/32 before the enclosing 240.0.0.0/4).
;;; :start/:end are pre-computed longs to avoid re-parsing on every call.
(def ^:private special-ranges
  (mapv (fn [{:keys [cidr] :as entry}]
          (let [[s e] (subnet/cidr->range cidr)]
            (assoc entry :start s :end e)))
        [;; /32
         {:cidr "255.255.255.255/32" :name "Limited Broadcast"         :rfc "RFC 919"}
         ;; /24
         {:cidr "192.0.0.0/24"       :name "IETF Protocol Assignments" :rfc "RFC 6890"}
         {:cidr "192.0.2.0/24"       :name "Documentation TEST-NET-1"  :rfc "RFC 5737"}
         {:cidr "198.51.100.0/24"    :name "Documentation TEST-NET-2"  :rfc "RFC 5737"}
         {:cidr "203.0.113.0/24"     :name "Documentation TEST-NET-3"  :rfc "RFC 5737"}
         ;; /16
         {:cidr "169.254.0.0/16"     :name "Link-Local"                :rfc "RFC 3927"}
         {:cidr "192.168.0.0/16"     :name "Private"                   :rfc "RFC 1918"}
         ;; /15
         {:cidr "198.18.0.0/15"      :name "Benchmarking"              :rfc "RFC 2544"}
         ;; /12
         {:cidr "172.16.0.0/12"      :name "Private"                   :rfc "RFC 1918"}
         ;; /10
         {:cidr "100.64.0.0/10"      :name "Shared Address Space"      :rfc "RFC 6598"}
         ;; /8
         {:cidr "10.0.0.0/8"         :name "Private"                   :rfc "RFC 1918"}
         {:cidr "127.0.0.0/8"        :name "Loopback"                  :rfc "RFC 1122"}
         {:cidr "0.0.0.0/8"          :name "This Network"              :rfc "RFC 1122"}
         ;; /4
         {:cidr "240.0.0.0/4"        :name "Reserved"                  :rfc "RFC 1112"}
         {:cidr "224.0.0.0/4"        :name "Multicast"                 :rfc "RFC 1112"}]))

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
        spans?   (and is-cidr? (not= net-m bcast-m))]
    (merge {:input input :routable? (= "Public" (:name net-m)) :spans? spans?}
           net-m
           (when spans? {:bcast-name (:name bcast-m)}))))
