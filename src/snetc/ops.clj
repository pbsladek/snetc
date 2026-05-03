(ns snetc.ops
  "Set operations on CIDR collections: aggregation, diff, overlaps, VLSM, LPM."
  (:require [clojure.string :as str]
            [snetc.addr     :as addr]
            [snetc.ip       :as ip]
            [snetc.subnet   :as subnet]))

(defn- cidr-entry [cidr]
  (let [{:keys [family bits prefix network last cidr]} (addr/parse-cidr cidr)]
    {:family family
     :bits bits
     :cidr cidr
     :prefix prefix
     :range [network last]
     :start network
     :end last}))

(defn- require-single-family! [entries op-name]
  (when (seq entries)
    (let [family (:family (first entries))]
      (when-let [mixed (first (filter #(not= family (:family %)) entries))]
        (throw (ex-info (str op-name " requires a single address family; mixed "
                             (name family) " and " (name (:family mixed)) " inputs")
                        {:operation op-name
                         :family family
                         :mixed-family (:family mixed)})))
      family)))

(defn- ranges->cidrs [family ranges]
  (vec (mapcat (fn [[s e]] (addr/range->cidrs family s e)) ranges)))

(defn- merge-ranges
  "Returns the minimal non-overlapping set of ranges from a sorted seq."
  [ranges]
  (reduce (fn [acc [s e]]
            (let [[as ae] (peek acc)]
              (if (and ae (<= s (inc ae)))
                (conj (pop acc) [as (max ae e)])
                (conj acc [s e]))))
          []
          ranges))

(defn- subtract-ranges
  "Returns the parts of a-ranges not covered by b-ranges.
  Both seqs must be sorted and non-overlapping.

  Safety note: when bs <= pos (subtrahend starts at/before current position),
  the recur advances pos to (inc be). This is safe only because the sorted
  non-overlapping precondition guarantees be >= pos at that point — all callers
  pass output from merge-ranges which enforces this invariant."
  [a-ranges b-ranges]
  (mapcat
    (fn [[as ae]]
      (let [relevant (filter (fn [[bs be]] (and (<= bs ae) (>= be as))) b-ranges)]
        (loop [pos as
               subs relevant
               acc  []]
          (cond
            (> pos ae)    acc
            (empty? subs) (conj acc [pos ae])
            :else
            (let [[bs be] (first subs)]
              (if (> bs pos)
                (recur (inc be) (rest subs) (conj acc [pos (dec bs)]))
                (recur (inc be) (rest subs) acc)))))))
    a-ranges))

(defn aggregate
  "Returns the minimal CIDR set covering the same address space as cidrs, as a vector."
  [cidrs]
  (let [entries (mapv cidr-entry cidrs)
        family  (require-single-family! entries "aggregate")]
    (if (nil? family)
      []
      (->> entries
           (map :range)
           (sort-by first)
           merge-ranges
           (ranges->cidrs family)))))

(defn free-space
  "Returns CIDR strings for unallocated space in parent-cidr after removing allocated-cidrs.
  Allocations that extend beyond the parent boundary are clipped."
  [parent-cidr allocated-cidrs]
  (let [parent-entry {:input parent-cidr :entry (cidr-entry parent-cidr)}
        alloc-entries (mapv (fn [cidr] {:input cidr :entry (cidr-entry cidr)})
                            allocated-cidrs)
        all-entries (mapv :entry (cons parent-entry alloc-entries))
        family (require-single-family! all-entries "free")
        {:keys [start end]} (:entry parent-entry)
        used (->> alloc-entries
                  (map (comp :range :entry))
                  (filter (fn [[s e]] (and (<= s end) (>= e start)))) ; any overlap
                  (map    (fn [[s e]] [(max s start) (min e end)]))   ; clip to parent
                  (sort-by first)
                  merge-ranges)
        gaps (loop [pos start
                    rs  used
                    acc []]
               (if (empty? rs)
                 (if (<= pos end) (conj acc [pos end]) acc)
                 (let [[s e] (first rs)]
                   (recur (inc e)
                          (rest rs)
                          (if (< pos s) (conj acc [pos (dec s)]) acc)))))]
    (ranges->cidrs family gaps)))

(defn cidr-diff
  "Returns {:added :removed :unchanged} CIDR lists comparing before-cidrs to after-cidrs.
  Both sets are aggregated before comparison.

  Design note: :unchanged is derived from the before-ranges (br) minus what was
  removed, i.e. the parts of the old set that still exist in the new set. It is
  intentionally expressed in terms of the before address space — if the same
  address space is split differently between before and after, :unchanged reflects
  the before-side CIDR blocks."
  [before-cidrs after-cidrs]
  (let [before-entries (mapv cidr-entry before-cidrs)
        after-entries  (mapv cidr-entry after-cidrs)
        all-entries    (vec (concat before-entries after-entries))
        family         (require-single-family! all-entries "diff")
        br        (->> before-entries (map :range) (sort-by first) merge-ranges)
        ar        (->> after-entries  (map :range) (sort-by first) merge-ranges)
        added-r   (subtract-ranges ar br)
        removed-r (subtract-ranges br ar)
        ->cidrs   (fn [ranges] (if family (ranges->cidrs family ranges) []))]
    {:added     (->cidrs added-r)
     :removed   (->cidrs removed-r)
     ;; unchanged = before minus removed = what stayed the same, as before-side CIDRs.
     :unchanged (->cidrs (subtract-ranges br removed-r))}))

(defn- overlap-type [[s1 e1] [s2 e2]]
  (cond
    (and (<= s1 s2) (>= e1 e2)) :a-contains-b
    (and (<= s2 s1) (>= e2 e1)) :b-contains-a
    :else                        :partial))

(defn find-overlaps
  "Returns all overlapping pairs in cidrs as {:a :b :type} maps.
  :type is :a-contains-b, :b-contains-a, or :partial."
  [cidrs]
  (let [entries (mapv cidr-entry cidrs)
        _       (require-single-family! entries "overlaps")
        indexed (->> entries
                     (map-indexed (fn [i {:keys [cidr range start end]}]
                                    {:idx i :cidr cidr :range range :start start :end end}))
                     (sort-by (juxt :start :end :idx)))]
    (loop [remaining indexed
           active []
           acc []]
      (if-let [{:keys [start range cidr idx] :as current} (first remaining)]
        (let [active (filterv #(>= (:end %) start) active)
              pairs  (keep (fn [{other-idx :idx other-cidr :cidr other-range :range}]
                             (let [pair [(min other-idx idx) (max other-idx idx)]
                                   [a-cidr b-cidr a-range b-range]
                                   (if (< other-idx idx)
                                     [other-cidr cidr other-range range]
                                     [cidr other-cidr range other-range])]
                               {:pair pair
                                :a a-cidr
                                :b b-cidr
                                :type (overlap-type a-range b-range)}))
                           active)]
          (recur (rest remaining) (conj active current) (into acc pairs)))
        (mapv #(dissoc % :pair) (sort-by :pair acc))))))

(defn longest-prefix-match
  "Returns the longest-prefix-matching CIDR from routes for ip, or nil."
  [ip routes]
  (let [parsed-ip (addr/parse-ip ip)
        entries   (mapv cidr-entry routes)
        family    (require-single-family! entries "lpm")]
    (when (and family (not= family (:family parsed-ip)))
      (throw (ex-info (str "lpm requires a single address family; mixed "
                           (name family) " route and "
                           (name (:family parsed-ip)) " address")
                      {:operation "lpm"
                       :family family
                       :mixed-family (:family parsed-ip)})))
    (:cidr
     (reduce (fn [best {:keys [prefix start end] :as route}]
               (if (and (<= start (:addr parsed-ip) end)
                        (> prefix (:prefix best -1)))
                 route
                 best))
             {:prefix -1 :cidr nil}
             entries))))

(defn supernet
  "Returns the smallest single CIDR string that covers all given cidrs.
  Walks up the prefix tree until it finds a block that contains every input range."
  [cidrs]
  (let [entries   (mapv cidr-entry cidrs)
        family    (require-single-family! entries "supernet")
        bits      (:bits (first entries))
        ranges    (mapv :range entries)
        min-start (apply min (map first ranges))
        max-end   (apply max (map second ranges))]
    (loop [prefix bits]
      (when (< prefix 0)
        (throw (ex-info "Cannot find covering supernet (exhausted all prefixes)" {})))
      (let [net  (addr/network-addr family min-start prefix)
            last (addr/last-addr family net prefix)]
        (if (<= max-end last)
          (first (addr/range->cidrs family net last))
          (recur (dec prefix)))))))

(defn hosts->min-prefix
  "Returns the smallest prefix length whose usable host count is >= n."
  [n]
  (when (< n 1)
    (throw (ex-info (str "Host count must be ≥ 1, got: " n) {:n n})))
  (loop [p 32]
    (cond
      (< p 0)                (throw (ex-info (str "No prefix can fit " n " hosts") {:n n}))
      (>= (ip/usable-hosts p) n) p
      :else                  (recur (dec p)))))

(defn next-available
  "Returns the first available aligned CIDR for n hosts within parent-cidr,
  given already-allocated CIDRs. Returns nil if no block fits."
  [parent-cidr allocated-cidrs n]
  (let [prefix (hosts->min-prefix n)
        size   (bit-shift-left 1 (- 32 prefix))
        gaps   (free-space parent-cidr allocated-cidrs)]
    (some (fn [gap-cidr]
            (let [[gs ge] (subnet/cidr->range gap-cidr)
                  remainder (mod gs size)
                  aligned   (if (zero? remainder) gs (+ gs (- size remainder)))]
              (when (<= (+ aligned size -1) ge)
                (str (ip/long->ip aligned) "/" prefix))))
          gaps)))

(defn next-available-prefix
  "Returns the first available aligned CIDR with requested prefix within parent-cidr."
  [parent-cidr allocated-cidrs requested-prefix]
  (let [{:keys [family prefix]} (cidr-entry parent-cidr)]
    (when-not (addr/valid-prefix? family requested-prefix)
      (throw (ex-info (str "Prefix must be 0-" (:bits (cidr-entry parent-cidr))
                           ", got: " requested-prefix)
                      {:parent parent-cidr :requested-prefix requested-prefix})))
    (when (< requested-prefix prefix)
      (throw (ex-info (str "Requested prefix /" requested-prefix
                           " is smaller than parent /" prefix)
                      {:parent parent-cidr :requested-prefix requested-prefix})))
    (let [size (addr/address-count family requested-prefix)]
      (some (fn [gap-cidr]
              (let [{:keys [start end]} (addr/cidr->range gap-cidr)
                    remainder (mod start size)
                    aligned   (if (zero? remainder) start (+ start (- size remainder)))
                    block-end (dec (+ aligned size))]
                (when (<= block-end end)
                  (first (addr/range->cidrs family aligned block-end)))))
            (free-space parent-cidr allocated-cidrs)))))

(defn- fragmentation-score [free-count]
  (cond
    (zero? free-count) nil
    (= 1 free-count)   "none"
    (<= free-count 3)  "low"
    (<= free-count 6)  "moderate"
    :else              "high"))

(def ^:private bar-width 72)

(defn- util-bar
  "Returns a bar-width string of █ (allocated) and ░ (free) characters."
  [allocated-cidrs parent-cidr]
  (let [{pstart :start pend :end} (addr/cidr->range parent-cidr)
        total   (inc (- pend pstart))
        aranges (mapv (fn [cidr]
                        (let [{:keys [start end]} (addr/cidr->range cidr)]
                          [start end]))
                      allocated-cidrs)]
    (apply str
           (for [i (range bar-width)]
             (let [addr (+ pstart (quot (* (bigint i) total) bar-width))]
               (if (some (fn [[s e]] (<= s addr e)) aranges) \█ \░))))))

(defn utilization-info
  "Returns utilization statistics for parent-cidr given a set of allocated-cidrs."
  [parent-cidr allocated-cidrs]
  (let [parsed-cidrs (mapv cidr-entry (cons parent-cidr allocated-cidrs))
        _ (require-single-family! parsed-cidrs "util")
        parent-info  (addr/subnet-info parent-cidr)
        {pstart :start pend :end} (addr/cidr->range parent-cidr)
        total-addrs  (inc (- pend pstart))
        free-cidrs   (vec (free-space parent-cidr allocated-cidrs))
        free-addrs   (reduce + 0N (map (fn [c]
                                          (let [{:keys [start end]} (addr/cidr->range c)]
                                            (inc (- end start))))
                                        free-cidrs))
        used-addrs   (- total-addrs free-addrs)
        alloc-infos  (mapv addr/subnet-info allocated-cidrs)
        free-infos   (mapv addr/subnet-info free-cidrs)
        largest-free (when (seq free-infos) (apply max-key :addresses free-infos))
        pct-used     (if (pos? total-addrs)
                       (long (Math/round (double (* 100 (/ used-addrs total-addrs)))))
                       0)]
    {:parent-info   parent-info
     :alloc-infos   alloc-infos
     :free-infos    free-infos
     :largest-free  largest-free
     :total-addrs   total-addrs
     :used-addrs    used-addrs
     :free-addrs    free-addrs
     :pct-used      pct-used
     :fragmentation (fragmentation-score (count free-infos))
     :bar           (util-bar allocated-cidrs parent-cidr)}))

(defn plan-prefixes
  "Returns a vector of prefix-size allocations within parent-cidr.
  Requests are allocated largest-first, which means lower prefix numbers first."
  [parent-cidr requested-prefixes]
  (let [{:keys [family prefix start end]} (cidr-entry parent-cidr)]
    (doseq [requested-prefix requested-prefixes]
      (when-not (addr/valid-prefix? family requested-prefix)
        (throw (ex-info (str "Prefix must be 0-" (:bits (cidr-entry parent-cidr))
                             ", got: " requested-prefix)
                        {:parent parent-cidr :requested-prefix requested-prefix})))
      (when (< requested-prefix prefix)
        (throw (ex-info (str "Requested prefix /" requested-prefix
                             " is smaller than parent /" prefix)
                        {:parent parent-cidr :requested-prefix requested-prefix}))))
    (loop [prefixes (sort requested-prefixes)
           pos      start
           result   []]
      (cond
        (empty? prefixes) result

        (> pos end)
        (throw (ex-info (str "Not enough space in " parent-cidr
                             " for remaining allocations") {}))

        :else
        (let [requested-prefix (first prefixes)
              size             (addr/address-count family requested-prefix)
              remainder        (mod pos size)
              aligned          (if (zero? remainder) pos (+ pos (- size remainder)))
              block-end        (dec (+ aligned size))]
          (when (> block-end end)
            (throw (ex-info (str "Not enough space in " parent-cidr
                                 " to fit a /" requested-prefix) {})))
          (let [cidr (first (addr/range->cidrs family aligned block-end))]
            (recur (rest prefixes)
                   (inc block-end)
                   (conj result {:info             (addr/subnet-info cidr)
                                 :requested        (str "/" requested-prefix)
                                 :requested-prefix requested-prefix}))))))))

(def ^:private cidr-pat #"(?i)[0-9a-f:.]+/\d{1,3}")

(defn parse-routes
  "Extracts destination CIDRs from route table text.
  Accepts ip-route show output, Cisco IOS show ip route, or plain CIDR lists.
  Returns a distinct vec of normalised CIDR strings."
  [text]
  (->> (str/split-lines text)
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (keep (fn [line]
               (some (fn [candidate]
                       (try (:cidr (addr/subnet-info candidate))
                            (catch Exception _ nil)))
                     (re-seq cidr-pat line))))
       distinct
       vec))

(defn- routes-within
  "Returns the subset of routes whose address range falls entirely within cidr."
  [routes cidr]
  (let [{as :start ae :end} (addr/cidr->range cidr)]
    (filterv (fn [r]
               (let [{rs :start re :end} (addr/cidr->range r)]
                 (and (>= rs as) (<= re ae))))
             routes)))

(defn analyze-routes
  "Returns analysis of a CIDR route table: containments, summarization groups, stats."
  [routes]
  (let [aggregated  (aggregate routes)
        family      (some-> (first routes) addr/parse-cidr :family)
        groups      (->> aggregated
                         (map (fn [agg]
                                {:summary agg
                                 :routes  (routes-within routes agg)}))
                         (filter #(> (count (:routes %)) 1))
                         vec)
        contained   (filterv #(#{:a-contains-b :b-contains-a} (:type %))
                              (find-overlaps routes))]
    {:family           family
     :routes           routes
     :route-count      (count routes)
     :aggregated       aggregated
     :aggregated-count (count aggregated)
     :savings          (- (count routes) (count aggregated))
     :groups           groups
     :contained        contained}))

(defn plan-vlsm
  "Returns a vector of {:info :requested} VLSM allocations for host-counts within parent-cidr.
  Allocates largest subnets first to minimise alignment waste.

  Design limitation: address space consumed by alignment padding between subnets is
  not tracked in the output. Callers that need to account for waste should call
  (ops/free-space parent-cidr allocated-cidrs) post-hoc."
  [parent-cidr host-counts]
  (let [[pstart pend] (subnet/cidr->range parent-cidr)]
    (loop [counts (sort > host-counts)
           pos    pstart
           result []]
      (cond
        (empty? counts) result
        ;; Guard: pos can reach (inc 0xFFFFFFFF) = 2^32 after the last allocation.
        ;; Terminate early to avoid alignment arithmetic on an out-of-range value.
        (> pos pend)
        (throw (ex-info (str "Not enough space in " parent-cidr
                             " for remaining allocations") {}))
        :else
        (let [n         (first counts)
              prefix    (hosts->min-prefix n)
              size      (bit-shift-left 1 (- 32 prefix))
              remainder (mod pos size)
              aligned   (if (zero? remainder) pos (+ pos (- size remainder)))]
          (when (> aligned pend)
            (throw (ex-info (str "Not enough space in " parent-cidr
                                 " to fit a /" prefix " (need alignment gap)") {})))
          (let [blk-end (dec (+ aligned size))]
            (when (> blk-end pend)
              (throw (ex-info (str "Not enough space in " parent-cidr
                                   " to fit a /" prefix " for " n " hosts") {})))
            (recur (rest counts)
                   (inc blk-end)
                   (conj result {:info      (subnet/subnet-info
                                              (str (ip/long->ip aligned) "/" prefix))
                                 :requested n}))))))))
