(ns snetc.plan
  "Pure model for interactive subnet planning."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [snetc.ip :as ip]
            [snetc.subnet :as subnet]))

(def plan-version 1)

(defn- normalize-cidr [cidr]
  (:cidr (subnet/subnet-info cidr)))

(defn leaf?
  "Returns true when node is a leaf subnet in the plan tree."
  [node]
  (empty? (:children node)))

(defn new-plan
  "Returns a new interactive plan rooted at parent-cidr."
  [parent-cidr]
  (let [cidr (normalize-cidr parent-cidr)]
    {:version plan-version
     :parent cidr
     :root {:cidr cidr :label nil :children nil}
     :cursor cidr
     :undo []
     :redo []}))

(defn- cidr-prefix [cidr]
  (:prefix (subnet/parse-cidr cidr)))

(defn split-once
  "Returns the two child CIDRs produced by splitting cidr one prefix longer."
  [cidr]
  (let [cidr (normalize-cidr cidr)
        prefix (cidr-prefix cidr)]
    (when (= 32 prefix)
      (throw (ex-info (str "Cannot split /32 subnet: " cidr) {:cidr cidr})))
    (mapv :cidr (subnet/split-subnets cidr (inc prefix)))))

(defn parent-cidr
  "Returns the immediate parent CIDR of cidr, or nil for /0."
  [cidr]
  (let [{:keys [ip-str prefix]} (subnet/parse-cidr cidr)]
    (when (pos? prefix)
      (let [parent-prefix (dec prefix)
            net (ip/network-addr (ip/ip->long ip-str) parent-prefix)]
        (str (ip/long->ip net) "/" parent-prefix)))))

(defn sibling-cidr
  "Returns cidr's immediate sibling CIDR, or nil for /0."
  [cidr]
  (when-let [parent (parent-cidr cidr)]
    (some #(when (not= % (normalize-cidr cidr)) %)
          (split-once parent))))

(defn- sort-leaves [nodes]
  (sort-by (comp first subnet/cidr->range :cidr) nodes))

(defn- leaves* [node depth]
  (if (leaf? node)
    [(assoc node :depth depth)]
    (mapcat #(leaves* % (inc depth)) (:children node))))

(defn leaves
  "Returns current leaf nodes, sorted by network address and annotated with :depth."
  [plan]
  (vec (sort-leaves (leaves* (:root plan) 0))))

(defn leaf-cidrs
  "Returns current leaf CIDR strings in address order."
  [plan]
  (mapv :cidr (leaves plan)))

(defn- find-node* [node cidr]
  (cond
    (= (:cidr node) cidr) node
    (:children node) (some #(find-node* % cidr) (:children node))
    :else nil))

(defn find-node
  "Returns the node for cidr in plan, or nil."
  [plan cidr]
  (find-node* (:root plan) (normalize-cidr cidr)))

(defn can-split?
  "Returns true when cidr names a leaf that is not /32."
  [plan cidr]
  (let [cidr (normalize-cidr cidr)
        node (find-node plan cidr)]
    (boolean (and node (leaf? node) (< (cidr-prefix cidr) 32)))))

(defn- sibling-leaf? [plan cidr]
  (when-let [sibling (sibling-cidr cidr)]
    (when-let [node (find-node plan sibling)]
      (leaf? node))))

(defn can-join?
  "Returns true when cidr names a leaf whose sibling is also a leaf."
  [plan cidr]
  (let [cidr (normalize-cidr cidr)
        node (find-node plan cidr)]
    (boolean (and node (leaf? node) (parent-cidr cidr) (sibling-leaf? plan cidr)))))

(defn- snapshot [plan]
  (select-keys plan [:root :cursor]))

(defn- restore-snapshot [plan snap]
  (assoc plan :root (:root snap) :cursor (:cursor snap)))

(defn- with-history [plan new-root new-cursor]
  (-> plan
      (update :undo conj (snapshot plan))
      (assoc :root new-root
             :cursor new-cursor
             :redo [])))

(defn- update-node [node cidr f]
  (cond
    (= (:cidr node) cidr)
    (f node)

    (:children node)
    (update node :children #(mapv (fn [child] (update-node child cidr f)) %))

    :else
    node))

(defn- cidr-str [net prefix]
  (str (ip/long->ip net) "/" prefix))

(defn- split-tree-to-prefix [net prefix target-prefix]
  (let [cidr (cidr-str net prefix)]
    (if (= prefix target-prefix)
      {:cidr cidr :label nil :children nil}
      (let [child-prefix (inc prefix)
            child-size (bit-shift-left 1 (- 32 child-prefix))]
        {:cidr cidr
         :label nil
         :children [(split-tree-to-prefix net child-prefix target-prefix)
                    (split-tree-to-prefix (+ net child-size) child-prefix target-prefix)]}))))

(defn- leftmost-leaf-cidr [node]
  (if (seq (:children node))
    (leftmost-leaf-cidr (first (:children node)))
    (:cidr node)))

(defn split-leaf
  "Returns plan with cidr split into two child leaves.
  Throws ex-info if cidr is not a splittable leaf."
  [plan cidr]
  (let [cidr (normalize-cidr cidr)]
    (when-not (can-split? plan cidr)
      (throw (ex-info (str "Cannot split subnet: " cidr) {:cidr cidr})))
    (let [children (mapv (fn [child] {:cidr child :label nil :children nil})
                         (split-once cidr))
          new-root (update-node (:root plan) cidr #(assoc % :children children))]
      (with-history plan new-root (:cidr (first children))))))

(defn split-leaf-to-prefix
  "Returns plan with cidr recursively split until every descendant is target-prefix.
  Throws ex-info if cidr is not a splittable leaf or target-prefix is invalid."
  [planner cidr target-prefix]
  (when-not (subnet/valid-prefix? target-prefix)
    (throw (ex-info (str "Target prefix must be 0–32, got: " target-prefix)
                    {:target-prefix target-prefix})))
  (let [cidr (normalize-cidr cidr)
        start-prefix (cidr-prefix cidr)]
    (when (< target-prefix start-prefix)
      (throw (ex-info (str "Target prefix /" target-prefix
                           " is smaller than selected /" start-prefix)
                      {:cidr cidr :target-prefix target-prefix})))
    (if (= target-prefix start-prefix)
      (assoc planner :cursor cidr)
      (do
        (when-not (can-split? planner cidr)
          (throw (ex-info (str "Cannot split subnet: " cidr) {:cidr cidr})))
        (let [[net _] (subnet/cidr->range cidr)
              subtree (split-tree-to-prefix net start-prefix target-prefix)
              existing (find-node planner cidr)
              subtree (assoc subtree :label (:label existing))
              new-root (update-node (:root planner) cidr (constantly subtree))]
          (with-history planner new-root (leftmost-leaf-cidr subtree)))))))

(defn hosts->target-prefix
  "Returns the smallest prefix whose usable host count is >= host-count."
  [host-count]
  (when (< host-count 1)
    (throw (ex-info (str "Host count must be >= 1, got: " host-count)
                    {:host-count host-count})))
  (loop [p 32]
    (cond
      (< p 0) (throw (ex-info (str "No IPv4 subnet can fit " host-count " hosts")
                              {:host-count host-count}))
      (>= (ip/usable-hosts p) host-count) p
      :else (recur (dec p)))))

(defn split-leaf-for-hosts
  "Splits cidr to the tightest prefix that provides at least host-count usable hosts."
  [planner cidr host-count]
  (split-leaf-to-prefix planner cidr (hosts->target-prefix host-count)))

(defn- joinable-parent? [node cidr]
  (and (:children node)
       (= 2 (count (:children node)))
       (some #(= cidr (:cidr %)) (:children node))
       (every? leaf? (:children node))))

(defn- join-node [node cidr]
  (cond
    (joinable-parent? node cidr)
    (assoc node :children nil)

    (:children node)
    (update node :children #(mapv (fn [child] (join-node child cidr)) %))

    :else
    node))

(defn join-leaf
  "Returns plan with cidr and its sibling joined into their parent.
  Throws ex-info if cidr cannot be joined."
  [plan cidr]
  (let [cidr (normalize-cidr cidr)]
    (when-not (can-join? plan cidr)
      (throw (ex-info (str "Cannot join subnet: " cidr) {:cidr cidr})))
    (let [parent (parent-cidr cidr)
          new-root (join-node (:root plan) cidr)]
      (with-history plan new-root parent))))

(defn join-leaf-to-prefix
  "Returns plan after repeatedly joining cidr upward until target-prefix is reached."
  [planner cidr target-prefix]
  (when-not (subnet/valid-prefix? target-prefix)
    (throw (ex-info (str "Target prefix must be 0–32, got: " target-prefix)
                    {:target-prefix target-prefix})))
  (let [cidr (normalize-cidr cidr)
        start-prefix (cidr-prefix cidr)]
    (when (> target-prefix start-prefix)
      (throw (ex-info (str "Target prefix /" target-prefix
                           " is larger than selected /" start-prefix)
                      {:cidr cidr :target-prefix target-prefix})))
    (loop [p planner
           current cidr]
      (let [prefix (cidr-prefix current)]
        (cond
          (= prefix target-prefix) (assoc p :cursor current)
          (not (can-join? p current))
          (throw (ex-info (str "Cannot join " current " toward /" target-prefix)
                          {:cidr current :target-prefix target-prefix}))
          :else
          (let [p' (join-leaf p current)]
            (recur p' (:cursor p'))))))))

(defn label-leaf
  "Returns plan with label assigned to cidr. Blank labels are stored as nil."
  [plan cidr label]
  (let [cidr (normalize-cidr cidr)
        label (when-not (str/blank? label) label)]
    (when-not (find-node plan cidr)
      (throw (ex-info (str "Unknown subnet: " cidr) {:cidr cidr})))
    (with-history plan
                  (update-node (:root plan) cidr #(assoc % :label label))
                  cidr)))

(defn undo
  "Returns plan after one undo step, or plan unchanged when no undo is available."
  [plan]
  (if-let [snap (peek (:undo plan))]
    (-> plan
        (restore-snapshot snap)
        (update :undo pop)
        (update :redo conj (snapshot plan)))
    plan))

(defn redo
  "Returns plan after one redo step, or plan unchanged when no redo is available."
  [plan]
  (if-let [snap (peek (:redo plan))]
    (-> plan
        (restore-snapshot snap)
        (update :redo pop)
        (update :undo conj (snapshot plan)))
    plan))

(defn plan-ranges
  "Returns leaf ranges as [start end] pairs."
  [plan]
  (mapv (comp subnet/cidr->range :cidr) (leaves plan)))

(defn validate-plan
  "Returns true when plan leaves exactly partition the parent CIDR.
  Throws ex-info with context when the plan is invalid."
  [plan]
  (let [[pstart pend] (subnet/cidr->range (:parent plan))
        ranges (plan-ranges plan)]
    (when (empty? ranges)
      (throw (ex-info "Plan has no leaf subnets" {:parent (:parent plan)})))
    (when-not (= pstart (ffirst ranges))
      (throw (ex-info "Plan leaves do not start at parent network"
                      {:parent (:parent plan) :first-range (first ranges)})))
    (when-not (= pend (second (last ranges)))
      (throw (ex-info "Plan leaves do not end at parent broadcast"
                      {:parent (:parent plan) :last-range (last ranges)})))
    (doseq [[[s1 e1] [s2 e2]] (partition 2 1 ranges)]
      (when-not (= (inc e1) s2)
        (throw (ex-info "Plan leaves have a gap or overlap"
                        {:left [s1 e1] :right [s2 e2]}))))
    true))

(defn export-plan
  "Returns a stable EDN-ready representation of plan."
  [plan]
  {:version plan-version
   :parent (:parent plan)
   :root (:root plan)})

(defn import-plan
  "Returns a runtime plan from exported EDN data."
  [data]
  (let [data (if (string? data) (edn/read-string data) data)
        parent (normalize-cidr (:parent data))
        root (:root data)
        plan {:version (or (:version data) plan-version)
              :parent parent
              :root root
              :cursor (:cidr root)
              :undo []
              :redo []}]
    (when-not (= plan-version (:version plan))
      (throw (ex-info (str "Unsupported plan version: " (:version plan))
                      {:version (:version plan)})))
    (when-not (= parent (:cidr root))
      (throw (ex-info "Plan root does not match parent CIDR"
                      {:parent parent :root (:cidr root)})))
    (validate-plan plan)
    plan))
