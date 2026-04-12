(ns snetc.ops
  "Set operations on CIDR collections: aggregation, diff, overlaps, VLSM, LPM."
  (:require [snetc.ip     :as ip]
            [snetc.subnet :as subnet]))

;;; ── Internal range helpers ───────────────────────────────────────────────────

(defn- merge-ranges
  "Merge a sorted seq of [start end] ranges into the minimal non-overlapping set."
  [ranges]
  (reduce (fn [acc [s e]]
            (let [[as ae] (peek acc)]
              (if (and ae (<= s (inc ae)))
                (conj (pop acc) [as (max ae e)])
                (conj acc [s e]))))
          []
          ranges))

(defn- subtract-ranges
  "Remove all parts of `a-ranges` covered by `b-ranges`.
   Both sequences must already be sorted and non-overlapping."
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

;;; ── Aggregation ──────────────────────────────────────────────────────────────

(defn aggregate
  "Aggregate a collection of CIDR strings into the minimal covering set."
  [cidrs]
  (->> cidrs
       (map subnet/cidr->range)
       (sort-by first)
       merge-ranges
       (mapcat (fn [[s e]] (subnet/range->cidrs s e)))))

;;; ── Free space ───────────────────────────────────────────────────────────────

(defn free-space
  "Return CIDRs for unallocated space within parent-cidr after excluding allocated CIDRs.
   Allocated entries outside the parent are silently ignored."
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

;;; ── CIDR diff ────────────────────────────────────────────────────────────────

(defn cidr-diff
  "Compare two sets of CIDRs after aggregation.
   Returns {:added […] :removed […] :unchanged […]} as CIDR string lists."
  [before-cidrs after-cidrs]
  (let [br        (->> before-cidrs (map subnet/cidr->range) (sort-by first) merge-ranges)
        ar        (->> after-cidrs  (map subnet/cidr->range) (sort-by first) merge-ranges)
        added-r   (subtract-ranges ar br)
        removed-r (subtract-ranges br ar)
        ->cidrs   (fn [ranges] (vec (mapcat (fn [[s e]] (subnet/range->cidrs s e)) ranges)))]
    {:added     (->cidrs added-r)
     :removed   (->cidrs removed-r)
     :unchanged (->cidrs (subtract-ranges br removed-r))}))

;;; ── Overlap detection ────────────────────────────────────────────────────────

(defn- overlap-type [[s1 e1] [s2 e2]]
  (cond
    (and (<= s1 s2) (>= e1 e2)) :a-contains-b
    (and (<= s2 s1) (>= e2 e1)) :b-contains-a
    :else                        :partial))

(defn find-overlaps
  "Return all overlapping pairs from a collection of CIDRs.
   Each result is {:a cidr-a :b cidr-b :type :a-contains-b|:b-contains-a|:partial}."
  [cidrs]
  ;; Materialise to a vector so cidr->range is computed once per CIDR rather
  ;; than once per outer-loop iteration of the nested for comprehension.
  (let [indexed (mapv (fn [i c] [i c (subnet/cidr->range c)])
                      (range (count cidrs))
                      cidrs)]
    (for [[i ca ra] indexed
          [j cb rb] indexed
          :when (< i j)
          :when (let [[s1 e1] ra [s2 e2] rb] (and (<= s1 e2) (<= s2 e1)))]
      {:a ca :b cb :type (overlap-type ra rb)})))

;;; ── Longest prefix match ─────────────────────────────────────────────────────

(defn longest-prefix-match
  "Find the most specific (longest-prefix) CIDR from routes that contains ip.
   Returns the matching CIDR string, or nil if no route matches."
  [ip routes]
  ;; Parse each route once; reuse the parsed prefix for both containment check
  ;; and sort key, avoiding a second parse-cidr call per matching route.
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

;;; ── VLSM planner ─────────────────────────────────────────────────────────────

(defn hosts->min-prefix
  "Return the tightest (largest) prefix length whose usable host count >= n."
  [n]
  (when (< n 1)
    (throw (ex-info (str "Host count must be ≥ 1, got: " n) {:n n})))
  (loop [p 32]
    (cond
      (< p 0)                (throw (ex-info (str "No prefix can fit " n " hosts") {:n n}))
      (>= (ip/usable-hosts p) n) p
      :else                  (recur (dec p)))))

(defn plan-vlsm
  "Allocate subnets within parent-cidr for each host count in host-counts.
   Counts are sorted largest-first to minimise alignment waste.
   Returns a vector of {:info subnet-info-map :requested n} in allocation order."
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
