(ns snetc.spec
  "Specs for all snetc domain types and public functions."
  (:require [clojure.spec.alpha     :as s]
            [clojure.spec.gen.alpha :as gen]
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
  (s/keys :req-un [::network ::broadcast ::first-host ::last-host
                   ::hosts ::mask ::wildcard ::prefix ::cidr]))

;; Keys for classify results.
(s/def ::input      string?)
(s/def ::name       (s/and string? seq))
(s/def ::rfc        string?)
(s/def ::routable?  boolean?)
(s/def ::spans?     boolean?)
(s/def ::bcast-name string?)

(s/def ::classify-result
  (s/keys :req-un [::input ::name ::rfc ::routable? ::spans?]
          :opt-un [::bcast-name]))

;; Keys for overlap results.
(s/def ::a    ::cidr-str)
(s/def ::b    ::cidr-str)
(s/def ::type #{:a-contains-b :b-contains-a :partial})

(s/def ::overlap-result
  (s/keys :req-un [::a ::b ::type]))

;; Keys for VLSM allocation results.
(s/def ::requested pos-int?)
(s/def ::info      ::subnet-info-map)

(s/def ::vlsm-allocation
  (s/keys :req-un [::info ::requested]))

;; Keys for cidr-diff results.
(s/def ::added     (s/coll-of ::cidr-str :kind vector?))
(s/def ::removed   (s/coll-of ::cidr-str :kind vector?))
(s/def ::unchanged (s/coll-of ::cidr-str :kind vector?))

(s/def ::cidr-diff-result
  (s/keys :req-un [::added ::removed ::unchanged]))

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
                 ;; network address is always <= broadcast
                 (<= (ip/ip->long (:network ret))
                     (ip/ip->long (:broadcast ret)))))))

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
  (s/or :ip   ::ip-str
        :cidr ::cidr-str))

(s/fdef classify/classify
  :args (s/cat :input ::ip-or-cidr-str)
  :ret  ::classify-result
  :fn   (fn [{:keys [args ret]}]
          ;; :input in the result echoes the raw argument unchanged
          (= (:input ret) (second (:input args)))))

;; ── snetc.ops ────────────────────────────────────────────────────────────────

(s/fdef ops/aggregate
  :args (s/cat :cidrs (s/coll-of ::cidr-str))
  :ret  (s/coll-of ::cidr-str))

(s/fdef ops/free-space
  :args (s/cat :parent-cidr     ::cidr-str
               :allocated-cidrs (s/coll-of ::cidr-str))
  :ret  (s/coll-of ::cidr-str))

(s/fdef ops/cidr-diff
  :args (s/cat :before-cidrs (s/coll-of ::cidr-str)
               :after-cidrs  (s/coll-of ::cidr-str))
  :ret  ::cidr-diff-result)

(s/fdef ops/find-overlaps
  :args (s/cat :cidrs (s/coll-of ::cidr-str))
  :ret  (s/coll-of ::overlap-result))

(s/fdef ops/longest-prefix-match
  :args (s/cat :ip     ::ip-str
               :routes (s/coll-of ::cidr-str))
  :ret  (s/nilable ::cidr-str))

(s/fdef ops/hosts->min-prefix
  :args (s/cat :n ::host-count)
  :ret  ::prefix
  :fn   (fn [{:keys [args ret]}]
          ;; the returned prefix always has enough usable hosts
          (>= (ip/usable-hosts ret) (:n args))))

(s/fdef ops/plan-vlsm
  :args (s/cat :parent-cidr ::cidr-str
               :host-counts (s/coll-of ::host-count :min-count 1))
  :ret  (s/coll-of ::vlsm-allocation))
