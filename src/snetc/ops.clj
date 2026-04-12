(ns snetc.ops
  "Set operations on CIDR collections: aggregation, diff, overlaps, VLSM, LPM."
  (:require [snetc.ip     :as ip]
            [snetc.subnet :as subnet]))

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
  Both seqs must be sorted and non-overlapping."
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
  "Returns the minimal CIDR set covering the same address space as cidrs."
  [cidrs]
  (->> cidrs
       (map subnet/cidr->range)
       (sort-by first)
       merge-ranges
       (mapcat (fn [[s e]] (subnet/range->cidrs s e)))))

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
  Both sets are aggregated before comparison."
  [before-cidrs after-cidrs]
  (let [br        (->> before-cidrs (map subnet/cidr->range) (sort-by first) merge-ranges)
        ar        (->> after-cidrs  (map subnet/cidr->range) (sort-by first) merge-ranges)
        added-r   (subtract-ranges ar br)
        removed-r (subtract-ranges br ar)
        ->cidrs   (fn [ranges] (vec (mapcat (fn [[s e]] (subnet/range->cidrs s e)) ranges)))]
    {:added     (->cidrs added-r)
     :removed   (->cidrs removed-r)
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
    (for [[i ca ra] indexed
          [j cb rb] indexed
          :when (< i j)
          :when (let [[s1 e1] ra [s2 e2] rb] (and (<= s1 e2) (<= s2 e1)))]
      {:a ca :b cb :type (overlap-type ra rb)})))

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

(defn plan-vlsm
  "Returns a vector of {:info :requested} VLSM allocations for host-counts within parent-cidr.
  Allocates largest subnets first to minimise alignment waste."
  [parent-cidr host-counts]
  (let [[pstart pend] (subnet/cidr->range parent-cidr)]
    (loop [counts (sort > host-counts)
           pos    pstart
           result []]
      (if (empty? counts)
        result
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
