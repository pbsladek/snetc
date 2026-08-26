(ns snetc.spec
  "Specs for all snetc domain types and public functions."
  (:require [clojure.spec.alpha     :as s]
            [clojure.spec.gen.alpha :as gen]
            [snetc.addr             :as addr]
            [snetc.ip               :as ip]
            [snetc.subnet           :as subnet]
            [snetc.classify         :as classify]
            [snetc.ops              :as ops]))

;; ── Primitive types ───────────────────────────────────────────────────────────

;; A 32-bit unsigned integer held in a long.
(s/def ::ip-long
  (s/with-gen
    (s/and integer? #(<= 0 % 0xFFFFFFFF))
    #(gen/fmap long (gen/choose 0 0xFFFFFFFF))))

;; CIDR prefix length 0–32.
(s/def ::prefix
  (s/with-gen
    (s/and integer? #(<= 0 % 32))
    #(gen/choose 0 32)))

;; Valid dotted-decimal IPv4 string with no leading zeros.
(s/def ::ip-str
  (s/with-gen
    (s/and string? subnet/valid-ip?)
    #(gen/fmap (fn [[a b c d]] (str a "." b "." c "." d))
               (gen/tuple (gen/choose 0 255)
                          (gen/choose 0 255)
                          (gen/choose 0 255)
                          (gen/choose 0 255)))))

;; Valid CIDR string "a.b.c.d/n".
(s/def ::cidr-str
  (s/with-gen
    (s/and string?
           #(try (boolean (subnet/parse-cidr %)) (catch Exception _ false)))
    #(gen/fmap (fn [[a b c d p]] (str a "." b "." c "." d "/" p))
               (gen/tuple (gen/choose 0 255)
                          (gen/choose 0 255)
                          (gen/choose 0 255)
                          (gen/choose 0 255)
                          (gen/choose 0 32)))))

(def ^:private ipv6-samples
  ["::"
   "::1"
   "2001:db8::1"
   "2001:db8::ffff"
   "2001:db8:1::1"
   "fd00::1"
   "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"])

(def ^:private ipv6-cidr-samples
  ["::/0"
   "::1/128"
   "2001:db8::/32"
   "2001:db8::1/64"
   "2001:db8:1::/48"
   "fd00::/8"])

(defn- parses-as-family? [parse-fn family s]
  (try (= family (:family (parse-fn s)))
       (catch Exception _ false)))

(defn- parsed-family [parse-fn s]
  (try (:family (parse-fn s))
       (catch Exception _ nil)))

(defn- max-addr-for-family [family]
  (case family
    :ipv4 0xFFFFFFFFN
    :ipv6 (dec (bigint (.shiftLeft java.math.BigInteger/ONE 128)))))

(defn- same-cidr-family? [cidrs]
  (let [families (->> cidrs (map #(parsed-family addr/parse-cidr %)) (remove nil?) set)]
    (<= (count families) 1)))

(defn- ip-and-routes-same-family? [{:keys [ip routes]}]
  (let [ip-family    (parsed-family addr/parse-ip ip)
        route-family (first (keep #(parsed-family addr/parse-cidr %) routes))]
    (or (nil? route-family) (= ip-family route-family))))

(defn- prefix-valid-for-parent? [parent-cidr requested-prefix]
  (try (let [{:keys [family prefix]} (addr/parse-cidr parent-cidr)]
         (and (addr/valid-prefix? family requested-prefix)
              (>= requested-prefix prefix)))
       (catch Exception _ false)))

;; Valid IPv6 literal supported by snetc.addr.
(s/def ::ipv6-str
  (s/with-gen
    (s/and string? #(parses-as-family? addr/parse-ip :ipv6 %))
    #(gen/elements ipv6-samples)))

;; Valid IPv6 CIDR supported by snetc.addr.
(s/def ::ipv6-cidr-str
  (s/with-gen
    (s/and string? #(parses-as-family? addr/parse-cidr :ipv6 %))
    #(gen/elements ipv6-cidr-samples)))

;; Valid IPv4 or IPv6 address literal.
(s/def ::addr-str
  (s/with-gen
    (s/and string? #(try (boolean (addr/parse-ip %)) (catch Exception _ false)))
    #(gen/one-of [(s/gen ::ip-str) (s/gen ::ipv6-str)])))

;; Valid IPv4 or IPv6 CIDR.
(s/def ::addr-cidr-str
  (s/with-gen
    (s/and string? #(try (boolean (addr/parse-cidr %)) (catch Exception _ false)))
    #(gen/one-of [(s/gen ::cidr-str) (s/gen ::ipv6-cidr-str)])))

(s/def ::same-family-cidrs
  (s/with-gen
    (s/and (s/coll-of ::addr-cidr-str :kind vector?)
           same-cidr-family?)
    #(gen/one-of [(gen/vector (s/gen ::cidr-str) 0 6)
                  (gen/vector (s/gen ::ipv6-cidr-str) 0 6)])))

(s/def ::same-family-cidrs-nonempty
  (s/with-gen
    (s/and ::same-family-cidrs seq)
    #(gen/one-of [(gen/vector (s/gen ::cidr-str) 1 6)
                  (gen/vector (s/gen ::ipv6-cidr-str) 1 6)])))

;; Inclusive [start end] range of ip-longs with start <= end.
(s/def ::ip-range
  (s/with-gen
    (s/and (s/tuple ::ip-long ::ip-long)
           (fn [[s e]] (<= s e)))
    #(gen/fmap (fn [[a b]] [(min a b) (max a b)])
               (gen/tuple (s/gen ::ip-long) (s/gen ::ip-long)))))

;; Host count that fits within an IPv4 subnet (1 to 2^32-2).
(s/def ::host-count
  (s/with-gen
    (s/and pos-int? #(<= % 4294967294))
    #(gen/choose 1 4294967294)))

(s/def ::family #{:ipv4 :ipv6})
(s/def ::addr-int (s/and integer? #(<= 0 %)))

;; ── Compound types ────────────────────────────────────────────────────────────

;; Keys for subnet-info maps.
(s/def ::network    ::ip-str)
(s/def ::broadcast  ::ip-str)
(s/def ::first-host ::ip-str)
(s/def ::last-host  ::ip-str)
(s/def ::hosts      (s/and integer? pos?))
(s/def ::mask       ::ip-str)
(s/def ::wildcard   ::ip-str)
(s/def ::cidr       ::cidr-str)

(s/def ::subnet-info-map
  ;; :broadcast is absent for /32 host routes — use :opt-un.
  (s/keys :req-un [::network ::first-host ::last-host
                   ::hosts ::mask ::wildcard ::prefix ::cidr]
          :opt-un [::broadcast]))

(s/def ::addr-parse-result
  (s/and map?
         #(contains? % :family)
         #(contains? % :bits)
         #(contains? % :addr)
         #(contains? % :text)
         #(s/valid? ::family (:family %))
         #(s/valid? ::addr-int (:addr %))))

(s/def ::addr-cidr-result
  (s/and map?
         #(contains? % :family)
         #(contains? % :bits)
         #(contains? % :prefix)
         #(contains? % :network)
         #(contains? % :last)
         #(contains? % :cidr)
         #(s/valid? ::family (:family %))
         #(<= 0 (:prefix %) (:bits %))
         #(<= (:network %) (:last %))))

(s/def ::addr-info-map
  (s/and map?
         #(contains? % :family)
         #(contains? % :network)
         #(contains? % :prefix)
         #(contains? % :cidr)
         #(s/valid? ::family (:family %))
         #(case (:family %)
            :ipv4 (s/valid? ::subnet-info-map %)
            :ipv6 (and (contains? % :first-address)
                       (contains? % :last-address)
                       (contains? % :addresses)
                       (not (contains? % :broadcast))
                       (not (contains? % :hosts))))))

(s/def ::addr-range-map
  (s/and map?
         #(contains? % :family)
         #(contains? % :start)
         #(contains? % :end)
         #(s/valid? ::family (:family %))
         #(<= 0 (:start %) (:end %) (max-addr-for-family (:family %)))))

(s/def ::addr-tree-node
  (s/and map?
         #(s/valid? ::addr-info-map (:info %))
         #(or (not (contains? % :children))
              (s/valid? ::addr-tree-children (:children %)))))
(s/def ::addr-tree-children
  (s/nilable (s/coll-of ::addr-tree-node :kind vector?)))

;; Keys for classify results.
(s/def ::input      string?)
(s/def ::name       (s/and string? seq))
(s/def ::rfc        string?)
(s/def ::routable?  boolean?)
(s/def ::spans?     boolean?)
(s/def ::bcast-name string?)
(s/def ::category-path
  (s/coll-of (s/keys :req-un [::name ::rfc])
             :kind vector?
             :min-count 1))

(s/def ::classify-result
  (s/keys :req-un [::input ::name ::rfc ::routable? ::spans?]
          :opt-un [::bcast-name ::category-path ::family]))

;; Keys for overlap results.
(s/def ::a    ::addr-cidr-str)
(s/def ::b    ::addr-cidr-str)
(s/def ::type #{:a-contains-b :b-contains-a :partial})

(s/def ::overlap-result
  (s/keys :req-un [::a ::b ::type]))

;; Keys for VLSM allocation results.
(s/def ::requested pos-int?)
(s/def ::info      ::subnet-info-map)

(s/def ::vlsm-allocation
  (s/keys :req-un [::info ::requested]))

(s/def ::requested-prefix (s/and integer? #(<= 0 % 128)))
(s/def ::prefix-allocation
  (s/and map?
         #(s/valid? ::addr-info-map (:info %))
         #(string? (:requested %))
         #(s/valid? ::requested-prefix (:requested-prefix %))))

(s/def ::allocation-result
  (s/and map?
         #(contains? % :parent-info)
         #(contains? % :alloc-infos)
         #(contains? % :free-infos)
         #(contains? % :total-addrs)
         #(contains? % :used-addrs)
         #(contains? % :free-addrs)
         #(contains? % :pct-used)
         #(contains? % :bar)))

;; Keys for cidr-diff results.
(s/def ::added     (s/coll-of ::addr-cidr-str :kind vector?))
(s/def ::removed   (s/coll-of ::addr-cidr-str :kind vector?))
(s/def ::unchanged (s/coll-of ::addr-cidr-str :kind vector?))

(s/def ::cidr-diff-result
  (s/keys :req-un [::added ::removed ::unchanged]))

;; ── snetc.addr ───────────────────────────────────────────────────────────────

(s/fdef addr/valid-prefix?
  :args (s/cat :family ::family :p integer?)
  :ret  boolean?)

(s/fdef addr/parse-ip
  :args (s/cat :s ::addr-str)
  :ret  ::addr-parse-result)

(s/fdef addr/parse-cidr
  :args (s/cat :cidr ::addr-cidr-str)
  :ret  ::addr-cidr-result)

(s/fdef addr/subnet-info
  :args (s/cat :cidr ::addr-cidr-str)
  :ret  ::addr-info-map)

(s/fdef addr/cidr->range
  :args (s/cat :cidr ::addr-cidr-str)
  :ret  ::addr-range-map)

(s/fdef addr/range->cidrs
  :args (s/with-gen
          (s/and (s/cat :family ::family :start ::addr-int :end ::addr-int)
                 (fn [{:keys [family start end]}]
                   (and (<= 0 start end)
                        (<= end (max-addr-for-family family)))))
          #(gen/elements [[:ipv4 0N 0N]
                          [:ipv4 167772160N 167772415N]
                          [:ipv4 0N 0xFFFFFFFFN]
                          [:ipv6 0N 0N]
                          [:ipv6 1N 3N]
                          [:ipv6 (:start (addr/cidr->range "2001:db8::/112"))
                           (:end (addr/cidr->range "2001:db8::/112"))]]))
  :ret  (s/nilable (s/coll-of ::addr-cidr-str :kind vector?)))

(s/fdef addr/address->text
  :args (s/with-gen
          (s/and (s/cat :family ::family :n ::addr-int)
                 (fn [{:keys [family n]}]
                   (<= n (max-addr-for-family family))))
          #(gen/elements [[:ipv4 0N]
                          [:ipv4 167772161N]
                          [:ipv4 0xFFFFFFFFN]
                          [:ipv6 0N]
                          [:ipv6 1N]
                          [:ipv6 (:addr (addr/parse-ip "2001:db8::1"))]]))
  :ret  ::addr-str)

(s/fdef addr/ip-in-cidr?
  :args (s/with-gen
          (s/cat :ip ::addr-str :cidr ::addr-cidr-str)
          #(gen/one-of [(gen/tuple (s/gen ::ip-str) (s/gen ::cidr-str))
                        (gen/tuple (s/gen ::ipv6-str) (s/gen ::ipv6-cidr-str))]))
  :ret  boolean?)

(s/fdef addr/split-subnets
  :args (s/with-gen
          (s/and (s/cat :cidr ::addr-cidr-str :new-prefix ::requested-prefix)
                 (fn [{:keys [cidr new-prefix]}]
                   (prefix-valid-for-parent? cidr new-prefix)))
          #(gen/elements [["10.0.0.0/24" 25]
                          ["10.0.0.0/31" 32]
                          ["2001:db8::/63" 64]
                          ["2001:db8::/127" 128]]))
  :ret  (s/coll-of ::addr-info-map :kind vector?))

(s/fdef addr/adjacent-cidr
  :args (s/with-gen
          (s/cat :cidr ::addr-cidr-str :n integer?)
          #(gen/elements [["10.0.0.0/24" 1N]
                          ["10.0.1.0/24" -1N]
                          ["2001:db8::/64" 1N]
                          ["2001:db8:0:1::/64" -1N]]))
  :ret  ::addr-cidr-str)

(s/fdef addr/subnet-tree
  :args (s/with-gen
          (s/and (s/cat :cidr ::addr-cidr-str :max-prefix ::requested-prefix)
                 (fn [{:keys [cidr max-prefix]}]
                   (prefix-valid-for-parent? cidr max-prefix)))
          #(gen/elements [["10.0.0.0/24" 25]
                          ["2001:db8::/63" 64]]))
  :ret  ::addr-tree-node)

;; ── snetc.ip ──────────────────────────────────────────────────────────────────

(s/fdef ip/ip->long
  :args (s/cat :ip ::ip-str)
  :ret  ::ip-long
  :fn   (fn [{:keys [args ret]}]
          ;; round-trips: long->ip(ip->long(s)) == s
          (= (ip/long->ip ret) (:ip args))))

(s/fdef ip/long->ip
  :args (s/cat :n ::ip-long)
  :ret  ::ip-str
  :fn   (fn [{:keys [args ret]}]
          ;; round-trips: ip->long(long->ip(n)) == n
          (= (ip/ip->long ret) (:n args))))

(s/fdef ip/prefix->mask
  :args (s/cat :prefix ::prefix)
  :ret  ::ip-long
  :fn   (fn [{:keys [args ret]}]
          ;; mask->prefix round-trips back to the original prefix
          (= (ip/mask->prefix ret) (:prefix args))))

(s/fdef ip/mask->prefix
  :args (s/cat :mask ::ip-long)
  :ret  ::prefix)

(s/fdef ip/wildcard-mask
  :args (s/cat :prefix ::prefix)
  :ret  ::ip-long
  :fn   (fn [{:keys [args ret]}]
          ;; mask XOR wildcard == 0xFFFFFFFF
          (= (bit-xor (ip/prefix->mask (:prefix args)) ret) 0xFFFFFFFF)))

(s/fdef ip/network-addr
  :args (s/cat :ip ::ip-long :prefix ::prefix)
  :ret  ::ip-long
  :fn   (fn [{:keys [args ret]}]
          ;; idempotent: masking the result again yields itself
          (= ret (ip/network-addr ret (:prefix args)))))

(s/fdef ip/broadcast-addr
  :args (s/cat :network ::ip-long :prefix ::prefix)
  :ret  ::ip-long
  :fn   (fn [{:keys [args ret]}]
          ;; broadcast is always >= the network address
          (>= ret (:network args))))

(s/fdef ip/usable-hosts
  :args (s/cat :prefix ::prefix)
  :ret  pos-int?)

;; ── snetc.subnet ─────────────────────────────────────────────────────────────

(s/fdef subnet/valid-ip?
  :args (s/cat :s string?)
  :ret  boolean?)

(s/fdef subnet/valid-prefix?
  :args (s/cat :p integer?)
  :ret  boolean?)

(s/fdef subnet/parse-cidr
  :args (s/cat :cidr ::cidr-str)
  :ret  (s/keys :req-un [::ip-str ::prefix]))

(s/fdef subnet/subnet-info
  :args (s/cat :cidr ::cidr-str)
  :ret  ::subnet-info-map
  :fn   (fn [{:keys [args ret]}]
          (let [{:keys [ip-str prefix]} (subnet/parse-cidr (:cidr args))
                net (ip/network-addr (ip/ip->long ip-str) prefix)]
            (and (= (:prefix ret) prefix)
                 (= (:network ret) (ip/long->ip net))
                 ;; /32 omits :broadcast; otherwise network <= broadcast.
                 (or (= prefix 32)
                     (<= (ip/ip->long (:network ret))
                         (ip/ip->long (:broadcast ret))))))))

(s/fdef subnet/cidr->range
  :args (s/cat :cidr ::cidr-str)
  :ret  ::ip-range
  :fn   (fn [{:keys [args ret]}]
          (let [[s e] ret
                {:keys [ip-str prefix]} (subnet/parse-cidr (:cidr args))
                net (ip/network-addr (ip/ip->long ip-str) prefix)]
            (and (= s net)
                 (= e (ip/broadcast-addr net prefix))))))

(s/fdef subnet/range->cidrs
  :args (s/cat :start ::ip-long :end ::ip-long)
  :ret  (s/nilable (s/coll-of ::cidr-str))
  :fn   (fn [{:keys [args ret]}]
          ;; returns nil iff start > end
          (if (<= (:start args) (:end args))
            (some? ret)
            (nil? ret))))

(s/fdef subnet/ip-in-cidr?
  :args (s/cat :ip ::ip-str :cidr ::cidr-str)
  :ret  boolean?)

(s/fdef subnet/split-subnets
  :args (s/and (s/cat :cidr ::cidr-str :new-prefix ::prefix)
               (fn [{:keys [cidr new-prefix]}]
                 ;; new-prefix must be >= the base prefix
                 (>= new-prefix (:prefix (subnet/parse-cidr cidr)))))
  :ret  (s/coll-of ::subnet-info-map))

;; subnet-tree produces a recursive structure; the recursive child spec
;; is defined after the parent so that s/def can resolve it lazily.
(s/def ::subnet-tree-node
  (s/keys :req-un [::info]
          :opt-un [::children]))
(s/def ::children
  (s/nilable (s/coll-of ::subnet-tree-node)))

(s/fdef subnet/subnet-tree
  :args (s/and (s/cat :cidr ::cidr-str :max-prefix ::prefix)
               (fn [{:keys [cidr max-prefix]}]
                 (>= max-prefix (:prefix (subnet/parse-cidr cidr)))))
  :ret  ::subnet-tree-node)

;; ── snetc.classify ───────────────────────────────────────────────────────────

;; Input to classify: either a bare IP or a CIDR string.
(s/def ::ip-or-cidr-str
  (s/or :ip   ::addr-str
        :cidr ::addr-cidr-str))

(s/fdef classify/classify
  :args (s/cat :input ::ip-or-cidr-str)
  :ret  ::classify-result
  :fn   (fn [{:keys [args ret]}]
          ;; :input in the result echoes the raw argument unchanged
          (= (:input ret) (second (:input args)))))

;; ── snetc.ops ────────────────────────────────────────────────────────────────

(s/fdef ops/aggregate
  :args (s/cat :cidrs ::same-family-cidrs)
  :ret  (s/coll-of ::addr-cidr-str :kind vector?))

(s/fdef ops/free-space
  :args (s/with-gen
          (s/and (s/cat :parent-cidr ::addr-cidr-str
                        :allocated-cidrs ::same-family-cidrs)
                 (fn [{:keys [parent-cidr allocated-cidrs]}]
                   (same-cidr-family? (cons parent-cidr allocated-cidrs))))
          #(gen/elements [["10.0.0.0/24" []]
                          ["10.0.0.0/24" ["10.0.0.0/25"]]
                          ["2001:db8::/63" []]
                          ["2001:db8::/63" ["2001:db8::/64"]]]))
  :ret  (s/coll-of ::addr-cidr-str :kind vector?))

(s/fdef ops/cidr-diff
  :args (s/with-gen
          (s/and (s/cat :before-cidrs ::same-family-cidrs
                        :after-cidrs  ::same-family-cidrs)
                 (fn [{:keys [before-cidrs after-cidrs]}]
                   (same-cidr-family? (concat before-cidrs after-cidrs))))
          #(gen/one-of [(gen/tuple (gen/vector (s/gen ::cidr-str) 0 6)
                                    (gen/vector (s/gen ::cidr-str) 0 6))
                        (gen/tuple (gen/vector (s/gen ::ipv6-cidr-str) 0 6)
                                    (gen/vector (s/gen ::ipv6-cidr-str) 0 6))]))
  :ret  ::cidr-diff-result)

(s/fdef ops/find-overlaps
  :args (s/cat :cidrs ::same-family-cidrs)
  :ret  (s/coll-of ::overlap-result :kind vector?))

(s/fdef ops/longest-prefix-match
  :args (s/with-gen
          (s/and (s/cat :ip ::addr-str :routes ::same-family-cidrs)
                 ip-and-routes-same-family?)
          #(gen/one-of [(gen/tuple (s/gen ::ip-str)
                                    (gen/vector (s/gen ::cidr-str) 0 6))
                        (gen/tuple (s/gen ::ipv6-str)
                                    (gen/vector (s/gen ::ipv6-cidr-str) 0 6))]))
  :ret  (s/nilable ::addr-cidr-str))

(s/fdef ops/supernet
  :args (s/cat :cidrs ::same-family-cidrs-nonempty)
  :ret  ::addr-cidr-str)

(s/fdef ops/hosts->min-prefix
  :args (s/cat :n ::host-count)
  :ret  ::prefix
  :fn   (fn [{:keys [args ret]}]
          ;; the returned prefix always has enough usable hosts
          (>= (ip/usable-hosts ret) (:n args))))

(s/fdef ops/next-available-prefix
  :args (s/with-gen
          (s/and (s/cat :parent-cidr ::addr-cidr-str
                        :allocated-cidrs ::same-family-cidrs
                        :requested-prefix ::requested-prefix)
                 (fn [{:keys [parent-cidr allocated-cidrs requested-prefix]}]
                   (and (same-cidr-family? (cons parent-cidr allocated-cidrs))
                        (prefix-valid-for-parent? parent-cidr requested-prefix))))
          #(gen/elements [["10.0.0.0/24" [] 25]
                          ["10.0.0.0/24" ["10.0.0.0/25"] 25]
                          ["2001:db8::/60" [] 64]
                          ["2001:db8::/60" ["2001:db8::/64"] 64]]))
  :ret  (s/nilable ::addr-cidr-str))

(s/fdef ops/plan-vlsm
  :args (s/cat :parent-cidr ::cidr-str
               :host-counts (s/coll-of ::host-count :min-count 1))
  :ret  (s/coll-of ::vlsm-allocation))

(s/fdef ops/plan-prefixes
  :args (s/with-gen
          (s/and (s/cat :parent-cidr ::addr-cidr-str
                        :requested-prefixes (s/coll-of ::requested-prefix :kind vector? :min-count 1))
                 (fn [{:keys [parent-cidr requested-prefixes]}]
                   (every? #(prefix-valid-for-parent? parent-cidr %) requested-prefixes)))
          #(gen/elements [["10.0.0.0/24" [25]]
                          ["10.0.0.0/24" [25 25]]
                          ["2001:db8::/60" [64]]
                          ["2001:db8::/60" [64 64]]]))
  :ret  (s/coll-of ::prefix-allocation :kind vector?))

(s/fdef ops/utilization-info
  :args (s/with-gen
          (s/and (s/cat :parent-cidr ::addr-cidr-str
                        :allocated-cidrs ::same-family-cidrs)
                 (fn [{:keys [parent-cidr allocated-cidrs]}]
                   (same-cidr-family? (cons parent-cidr allocated-cidrs))))
          #(gen/elements [["10.0.0.0/24" ["10.0.0.0/25"]]
                          ["2001:db8::/63" ["2001:db8::/64"]]]))
  :ret  ::allocation-result)
