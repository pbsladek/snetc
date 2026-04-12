(ns snetc.ops
  "Set operations on CIDR collections: aggregation, diff, overlaps, VLSM, LPM."
  (:require [clojure.string :as str]
            [snetc.ip       :as ip]
            [snetc.subnet   :as subnet]))

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
  (->> cidrs
       (map subnet/cidr->range)
       (sort-by first)
       merge-ranges
       (mapcat (fn [[s e]] (subnet/range->cidrs s e)))
       vec))

(defn free-space
  "Returns CIDR strings for unallocated space in parent-cidr after removing allocated-cidrs.
  Allocations that extend beyond the parent boundary are clipped."
  [parent-cidr allocated-cidrs]
  (let [[pstart pend] (subnet/cidr->range parent-cidr)
        used  (->> allocated-cidrs
                   (map subnet/cidr->range)
                   (filter (fn [[s e]] (and (<= s pend) (>= e pstart)))) ; any overlap
                   (map    (fn [[s e]] [(max s pstart) (min e pend)]))    ; clip to parent
                   (sort-by first)
                   merge-ranges)
        gaps  (loop [pos  pstart
                     rs   used
                     acc  []]
                (if (empty? rs)
                  (if (<= pos pend) (conj acc [pos pend]) acc)
                  (let [[s e] (first rs)]
                    (recur (inc e)
                           (rest rs)
                           (if (< pos s) (conj acc [pos (dec s)]) acc)))))]
    (mapcat (fn [[s e]] (subnet/range->cidrs s e)) gaps)))

(defn cidr-diff
  "Returns {:added :removed :unchanged} CIDR lists comparing before-cidrs to after-cidrs.
  Both sets are aggregated before comparison.

  Design note: :unchanged is derived from the before-ranges (br) minus what was
  removed, i.e. the parts of the old set that still exist in the new set. It is
  intentionally expressed in terms of the before address space — if the same
  address space is split differently between before and after, :unchanged reflects
  the before-side CIDR blocks."
  [before-cidrs after-cidrs]
  (let [br        (->> before-cidrs (map subnet/cidr->range) (sort-by first) merge-ranges)
        ar        (->> after-cidrs  (map subnet/cidr->range) (sort-by first) merge-ranges)
        added-r   (subtract-ranges ar br)
        removed-r (subtract-ranges br ar)
        ->cidrs   (fn [ranges] (vec (mapcat (fn [[s e]] (subnet/range->cidrs s e)) ranges)))]
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
  ;; mapv so cidr->range is called once per CIDR, not once per pair.
  (let [indexed (mapv (fn [i c] [i c (subnet/cidr->range c)])
                      (range (count cidrs))
                      cidrs)]
    (vec (for [[i ca ra] indexed
               [j cb rb] indexed
               :when (< i j)
               :when (let [[s1 e1] ra [s2 e2] rb] (and (<= s1 e2) (<= s2 e1)))]
           {:a ca :b cb :type (overlap-type ra rb)}))))

(defn longest-prefix-match
  "Returns the longest-prefix-matching CIDR from routes for ip, or nil."
  [ip routes]
  ;; keep yields [prefix route] so the parsed prefix serves both the
  ;; containment test and the sort key without a second parse-cidr call.
  (let [ip-n (ip/ip->long ip)]
    (->> routes
         (keep (fn [route]
                 (let [{:keys [ip-str prefix]} (subnet/parse-cidr route)
                       net (ip/network-addr (ip/ip->long ip-str) prefix)]
                   (when (= net (ip/network-addr ip-n prefix))
                     [prefix route]))))
         (sort-by first)
         last
         second)))

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
  (let [[pstart pend] (subnet/cidr->range parent-cidr)
        total   (inc (- pend pstart))
        aranges (mapv subnet/cidr->range allocated-cidrs)]
    (apply str
           (for [i (range bar-width)]
             (let [addr (+ pstart (long (/ (* (long i) total) bar-width)))]
               (if (some (fn [[s e]] (<= s addr e)) aranges) \█ \░))))))

(defn utilization-info
  "Returns utilization statistics for parent-cidr given a set of allocated-cidrs."
  [parent-cidr allocated-cidrs]
  (let [parent-info  (subnet/subnet-info parent-cidr)
        [pstart pend] (subnet/cidr->range parent-cidr)
        total-addrs  (inc (- pend pstart))
        free-cidrs   (vec (free-space parent-cidr allocated-cidrs))
        free-addrs   (reduce + 0 (map (fn [c]
                                        (let [[s e] (subnet/cidr->range c)]
                                          (inc (- e s))))
                                      free-cidrs))
        used-addrs   (- total-addrs free-addrs)
        alloc-infos  (mapv subnet/subnet-info allocated-cidrs)
        free-infos   (mapv subnet/subnet-info free-cidrs)
        largest-free (when (seq free-infos) (apply max-key :hosts free-infos))
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

(def ^:private cidr-pat #"\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/\d{1,2}")

(defn parse-routes
  "Extracts destination CIDRs from route table text.
  Accepts ip-route show output, Cisco IOS show ip route, or plain CIDR lists.
  Returns a distinct vec of normalised CIDR strings."
  [text]
  (->> (str/split-lines text)
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (keep (fn [line]
               (when-let [m (re-find cidr-pat line)]
                 (try (:cidr (subnet/subnet-info m)) (catch Exception _ nil)))))
       distinct
       vec))

(defn- routes-within
  "Returns the subset of routes whose address range falls entirely within cidr."
  [routes cidr]
  (let [[as ae] (subnet/cidr->range cidr)]
    (filterv (fn [r]
               (let [[rs re] (subnet/cidr->range r)]
                 (and (>= rs as) (<= re ae))))
             routes)))

(defn analyze-routes
  "Returns analysis of a CIDR route table: containments, summarization groups, stats."
  [routes]
  (let [aggregated  (aggregate routes)
        groups      (->> aggregated
                         (map (fn [agg]
                                {:summary agg
                                 :routes  (routes-within routes agg)}))
                         (filter #(> (count (:routes %)) 1))
                         vec)
        contained   (filterv #(#{:a-contains-b :b-contains-a} (:type %))
                              (find-overlaps routes))]
    {:routes           routes
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
