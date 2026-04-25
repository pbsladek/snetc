(ns snetc.tui-model
  "Pure display model for the interactive subnet planner."
  (:require [clojure.string :as str]
            [snetc.ip :as ip]
            [snetc.plan :as plan]
            [snetc.subnet :as subnet]))

(defn- range-label [[start end]]
  (str (ip/long->ip start) ".." (ip/long->ip end)))

(defn- usable-label [{:keys [first-host last-host]}]
  (str first-host ".." last-host))

(defn- action-label [can-split? can-join?]
  (cond
    (and can-split? can-join?) "s/j"
    can-split? "s"
    can-join? "j"
    :else "-"))

(defn- row-map [idx depth label leaf-cidrs cidr]
  (let [info (subnet/subnet-info cidr)
        range (subnet/cidr->range cidr)
        can-split? (< (:prefix info) 32)
        can-join? (boolean (when-let [sibling (plan/sibling-cidr cidr)]
                             (contains? leaf-cidrs sibling)))]
    {:idx idx
     :cidr cidr
     :cidr-lower (str/lower-case cidr)
     :depth depth
     :label label
     :label-lower (str/lower-case (or label ""))
     :mask (:mask info)
     :range (range-label range)
     :start (first range)
     :end (second range)
     :usable (usable-label info)
     :hosts (:hosts info)
     :prefix (:prefix info)
     :can-split? can-split?
     :can-join? can-join?
     :action (action-label can-split? can-join?)}))

(defn rows
  "Returns display-ready row data for planner.
  The TUI caches this at state boundaries so rendering and selection do not
  repeatedly parse CIDRs or search the tree on every repaint."
  [planner]
  (let [leaves (plan/leaves planner)
        leaf-cidrs (set (map :cidr leaves))]
    (mapv (fn [idx {:keys [cidr depth label]}]
            (row-map (inc idx) depth label leaf-cidrs cidr))
          (range)
          leaves)))

(defn row
  "Returns one display row for cidr in planner, or nil when cidr is not a leaf."
  [planner cidr]
  (first (filter #(= cidr (:cidr %)) (rows planner))))

(defn replace-row
  "Returns rows with the row for cidr replaced from planner when it still exists."
  [rows planner cidr]
  (if-let [old-row (first (filter #(= cidr (:cidr %)) rows))]
    (if-let [node (plan/find-node planner cidr)]
      (let [leaf-cidrs (set (map :cidr rows))
            updated (row-map (:idx old-row) (:depth old-row) (:label node) leaf-cidrs cidr)]
        (mapv (fn [r] (if (= cidr (:cidr r)) updated r)) rows))
      rows)
    rows))

(defn- parse-prefix-query [q]
  (when-let [[_ p] (or (re-matches #"(?i)prefix:(\d{1,2})" q)
                       (re-matches #"/(\d{1,2})" q))]
    (let [n (Integer/parseInt p)]
      (when (subnet/valid-prefix? n) n))))

(defn row-matches-query?
  "Returns true when row matches q.
  Supported forms: prefix:N, label:text, ip:A.B.C.D, exact/partial CIDR, or
  free text against CIDR and label."
  [row q]
  (let [q (str/trim (or q ""))
        q-lower (str/lower-case q)
        label (:label-lower row)]
    (cond
      (str/blank? q) true

      (or (str/starts-with? q-lower "prefix:")
          (re-matches #"/\d{1,2}" q-lower))
      (= (parse-prefix-query q-lower)
         (:prefix row))

      (or (str/starts-with? q-lower "label:")
          (str/starts-with? q-lower "@"))
      (let [needle (if (str/starts-with? q-lower "@")
                     (subs q-lower 1)
                     (subs q-lower 6))]
        (str/includes? label needle))

      (str/starts-with? q-lower "ip:")
      (let [ip-str (subs q 3)]
        (and (subnet/valid-ip? ip-str)
             (let [ip-n (ip/ip->long ip-str)]
               (<= (:start row) ip-n (:end row)))))

      (subnet/valid-ip? q)
      (let [ip-n (ip/ip->long q)]
        (<= (:start row) ip-n (:end row)))

      (str/includes? q "/")
      (or (= (:cidr row) (try (:cidr (subnet/subnet-info q))
                              (catch Exception _ q)))
          (str/includes? (:cidr row) q))

      :else
      (or (str/includes? (:cidr-lower row) q-lower)
          (str/includes? label q-lower)))))

(defn filter-rows [rows q]
  (if (str/blank? (str q))
    rows
    (filterv #(row-matches-query? % q) rows)))

(defn prefix-histogram [rows]
  (->> rows
       (map :prefix)
       frequencies
       (sort-by key)
       (mapv (fn [[prefix n]] {:prefix prefix :count n}))))

(defn summary
  "Returns compact plan summary data for visible rows and selected row."
  [rows selected-row]
  {:visible-count (count rows)
   :selected selected-row
   :prefixes (prefix-histogram rows)})
