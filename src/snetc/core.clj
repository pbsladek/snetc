(ns snetc.core
  "CLI entry point for snetc. Thin dispatcher — all logic lives in sub-namespaces."
  (:require [clojure.string :as str]
            [snetc.ip      :as ip]
            [snetc.subnet  :as subnet]
            [snetc.ops     :as ops]
            [snetc.display :as display])
  (:gen-class))

;;; ── Usage ────────────────────────────────────────────────────────────────────

(defn- usage []
  (str/join \newline
            ["snetc – IPv4 subnet calculator"
             ""
             "Usage:"
             "  snetc <cidr>                              Show info for a subnet"
             "  snetc <cidr> --split <prefix>             List all /<prefix> subnets within <cidr>"
             "  snetc <cidr> --tree  <prefix>             Show split tree down to /<prefix>"
             "  snetc aggregate <cidr> [<cidr> ...]       Aggregate CIDRs to minimal covering set"
             "  snetc aggregate                           Read CIDRs from stdin (one per line)"
             "  snetc contains <cidr> <ip> [<ip> ...]     Check which IPs fall within a subnet"
             "  snetc free <parent> <alloc> [...]         Show unallocated space in a subnet"
             "  snetc plan <parent> <hosts> [<hosts> ...] VLSM: allocate subnets by host count"
             "  snetc overlaps <cidr> [<cidr> ...]        Detect overlapping/contained networks"
             "  snetc lpm <cidr|ip> ...                   Longest-prefix match (CIDRs=routes, IPs=lookups)"
             "  snetc diff <cidr> ... -- <cidr> ...       Diff two sets of CIDRs"
             "  snetc classify <ip-or-cidr> ...           RFC classification of IPs/CIDRs"
             "  snetc range <start-ip> <end-ip|+count>    Convert IP range to minimal CIDRs"
             ""
             "Examples:"
             "  snetc 192.168.0.0/22"
             "  snetc 192.168.0.0/22 --split 24"
             "  snetc 192.168.0.0/22 --tree 24"
             "  snetc aggregate 10.0.0.0/24 10.0.1.0/24"
             "  snetc contains 192.168.0.0/22 192.168.1.1 10.0.0.1"
             "  snetc free 192.168.0.0/22 192.168.0.0/24 192.168.2.0/23"
             "  snetc plan 192.168.0.0/22 500 200 50 10"
             "  snetc overlaps 10.0.0.0/8 10.0.0.0/24 192.168.0.0/16"
             "  snetc lpm 10.0.0.0/8 10.0.0.0/24 0.0.0.0/0 10.0.0.50 8.8.8.8"
             "  snetc diff 10.0.0.0/24 10.0.1.0/24 -- 10.0.0.0/23 10.0.2.0/24"
             "  snetc classify 10.0.0.1 192.168.1.1 8.8.8.8 127.0.0.1"
             "  snetc range 10.0.0.5 10.0.1.200"
             "  snetc range 10.0.0.0 +1000"]))

;;; ── Error helpers ────────────────────────────────────────────────────────────

(defn- die [msg]
  (binding [*out* *err*]
    (println msg))
  (System/exit 1))

(defn- parse-prefix! [s]
  (let [p (try (Integer/parseInt s)
               (catch Exception _ (die "Prefix must be an integer")))]
    (when-not (subnet/valid-prefix? p)
      (die "Prefix must be 0–32"))
    p))

;;; ── Subcommand handlers ──────────────────────────────────────────────────────

(defn- handle-info [args]
  (display/print-subnet-info (subnet/subnet-info (first args))))

(defn- handle-split [[cidr _ prefix-str]]
  (let [new-prefix (parse-prefix! prefix-str)
        base       (:prefix (subnet/subnet-info cidr))]
    (when (< new-prefix base)
      (die (str "Split prefix /" new-prefix " is smaller than base /" base)))
    (display/print-split-table (subnet/split-subnets cidr new-prefix))))

(defn- handle-tree [[cidr _ prefix-str]]
  (let [max-prefix (parse-prefix! prefix-str)
        base       (:prefix (subnet/subnet-info cidr))]
    (when (< max-prefix base)
      (die (str "Max prefix /" max-prefix " is smaller than base /" base)))
    (display/print-subnet-tree (subnet/subnet-tree cidr max-prefix))))

(defn- handle-aggregate [rest-args]
  (let [cidrs (if (seq rest-args)
                rest-args
                (->> (line-seq (java.io.BufferedReader. *in*))
                     (map str/trim)
                     (remove str/blank?)))]
    (display/print-aggregate-result cidrs (ops/aggregate cidrs))))

(defn- handle-contains [[cidr & ips]]
  (doseq [ip ips]
    (when-not (subnet/valid-ip? ip)
      (die (str "Invalid IP address: " ip))))
  (display/print-contains-result cidr ips))

(defn- handle-free [[parent & allocated]]
  (display/print-free-result parent allocated (ops/free-space parent allocated)))

(defn- handle-plan [[parent & host-strs]]
  (let [host-counts (mapv #(try (Integer/parseInt %)
                                (catch Exception _
                                  (die (str "Invalid host count: " %))))
                          host-strs)]
    (display/print-vlsm-result parent (ops/plan-vlsm parent host-counts))))

(defn- handle-overlaps [cidrs]
  (display/print-overlaps-result cidrs (ops/find-overlaps cidrs)))

(defn- handle-lpm [rest-args]
  (let [routes (filterv #(str/includes? % "/") rest-args)
        ips    (filterv #(not (str/includes? % "/")) rest-args)]
    (when (empty? routes) (die "lpm requires at least one route (CIDR with /)"))
    (when (empty? ips)    (die "lpm requires at least one IP to look up"))
    (doseq [ip ips]
      (when-not (subnet/valid-ip? ip) (die (str "Invalid IP address: " ip))))
    (display/print-lpm-result routes ips)))

(defn- handle-diff [rest-args]
  (let [v       (vec rest-args)
        sep-idx (.indexOf v "--")]
    (when (< sep-idx 1) (die "diff requires CIDRs on both sides of '--'"))
    (let [before (subvec v 0 sep-idx)
          after  (subvec v (inc sep-idx))]
      (when (empty? after) (die "diff requires at least one CIDR after '--'"))
      (display/print-diff-result before after (ops/cidr-diff before after)))))

(defn- handle-classify [inputs]
  (display/print-classify-result inputs))

(defn- handle-range [[start-ip end-arg]]
  (when-not (subnet/valid-ip? start-ip) (die (str "Invalid IP: " start-ip)))
  (let [start-n (ip/ip->long start-ip)
        end-n   (if (str/starts-with? end-arg "+")
                  (let [cnt (try (Long/parseLong (subs end-arg 1))
                                 (catch Exception _ (die "Count must be a positive integer")))]
                    (when (< cnt 1) (die "Count must be ≥ 1"))
                    (when (> cnt (- 0x100000000 start-n)) (die "Count exceeds available address space"))
                    (+ start-n cnt -1))
                  (do (when-not (subnet/valid-ip? end-arg) (die (str "Invalid IP: " end-arg)))
                      (ip/ip->long end-arg)))]
    (when (> start-n end-n) (die "Start IP must be ≤ end IP"))
    (when (> end-n 0xFFFFFFFF) (die "End address exceeds 255.255.255.255"))
    (display/print-range-result start-ip (ip/long->ip end-n)
                                (subnet/range->cidrs start-n end-n))))

;;; ── Dispatch table ───────────────────────────────────────────────────────────

(def ^:private subcommands
  {"aggregate" handle-aggregate
   "contains"  handle-contains
   "free"      handle-free
   "plan"      handle-plan
   "overlaps"  handle-overlaps
   "lpm"       handle-lpm
   "diff"      handle-diff
   "classify"  handle-classify
   "range"     handle-range})

;;; ── Entry point ──────────────────────────────────────────────────────────────

(defn -main [& args]
  (cond
    (or (empty? args) (= "--help" (first args)))
    (do (println (usage)) (System/exit 0))

    (= 1 (count args))
    (try (handle-info args)
         (catch Exception e (die (ex-message e))))

    (= "--split" (second args))
    (try (handle-split args)
         (catch Exception e (die (ex-message e))))

    (= "--tree" (second args))
    (try (handle-tree args)
         (catch Exception e (die (ex-message e))))

    :else
    (if-let [handler (subcommands (first args))]
      (try (handler (rest args))
           (catch Exception e (die (ex-message e))))
      (do (println (usage)) (System/exit 1)))))
