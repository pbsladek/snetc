(ns snetc.core
  "CLI entry point for snetc. Thin dispatcher — all logic lives in sub-namespaces."
  (:require [clojure.string    :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [snetc.ip          :as ip]
            [snetc.subnet      :as subnet]
            [snetc.ops         :as ops]
            [snetc.classify    :as classify]
            [snetc.display     :as display]
            [snetc.tui         :as tui])
  (:gen-class))

(def ^:private cli-options
  [[nil  "--split PREFIX" "List all /PREFIX subnets within CIDR"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 % 32) "Prefix must be 0–32"]]
   [nil  "--tree PREFIX"  "Show subnet split tree down to /PREFIX"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 % 32) "Prefix must be 0–32"]]
   ["-h" "--help"         "Print this help and exit"]])

(defn- usage [summary]
  (str/join \newline
            ["snetc – IPv4 subnet calculator"
             ""
             "Usage:"
             "  snetc <cidr> [options]"
             "  snetc <subcommand> [args...]"
             ""
             "Options:"
             summary
             ""
             "Subcommands:"
             "  aggregate <cidr> [...]      Aggregate CIDRs to minimal covering set"
             "  aggregate                   Read CIDRs from stdin (one per line)"
             "  contains <cidr> <ip> [...]  Check which IPs fall within a subnet"
             "  free <parent> <alloc> [...]  Show unallocated space in a subnet"
             "  plan <parent> <n> [...]      VLSM: allocate subnets by host count"
             "  overlaps <cidr> [...]        Detect overlapping/contained networks"
             "  lpm <cidr|ip> ...            Longest-prefix match"
             "  diff <cidr> ... -- <cidr>    Diff two sets of CIDRs"
             "  classify <ip-or-cidr> ...    RFC classification of IPs/CIDRs"
             "  range <start> <end|+count>   Convert IP range to minimal CIDRs"
             "  tree <cidr>                  Interactive split/join subnet planner"
             "  util <parent> <alloc> [...]  Visualise address space utilisation"
             "  analyze [<file>]             Analyse route table (or stdin)"]))

(defn- die [msg]
  (binding [*out* *err*]
    (println msg))
  (System/exit 1))

(defn- parse-long-or-nil [s]
  (try
    (Long/parseLong s)
    (catch Exception _ nil)))

(defn- index-of [xs needle]
  (or (first (keep-indexed (fn [idx x] (when (= needle x) idx)) xs))
      -1))

(defn- handle-info [cidr]
  (display/print-subnet-info (subnet/subnet-info cidr)))

(defn- handle-split [cidr new-prefix]
  (let [base (:prefix (subnet/subnet-info cidr))]
    (when (< new-prefix base)
      (die (str "Split prefix /" new-prefix " is smaller than base /" base)))
    (display/print-split-table (subnet/split-subnets cidr new-prefix))))

(defn- handle-tree [cidr max-prefix]
  (let [base (:prefix (subnet/subnet-info cidr))]
    (when (< max-prefix base)
      (die (str "Max prefix /" max-prefix " is smaller than base /" base)))
    (display/print-subnet-tree (subnet/subnet-tree cidr max-prefix))))

(defn- handle-aggregate [rest-args]
  (let [cidrs (if (seq rest-args)
                rest-args
                ;; Force the lazy stdin seq into a vector so it can be counted
                ;; and passed to aggregate without being consumed twice.
                (->> (line-seq (java.io.BufferedReader. *in*))
                     (map str/trim)
                     (remove str/blank?)
                     vec))]
    (when (empty? cidrs)
      (die "aggregate requires at least one CIDR (or CIDRs on stdin)"))
    (display/print-aggregate-result cidrs (ops/aggregate cidrs))))

(defn- handle-contains [[cidr & ips]]
  (when (nil? cidr) (die "contains requires a CIDR and at least one IP"))
  (when (empty? ips) (die "contains requires at least one IP address"))
  (let [parsed (try {:info (subnet/subnet-info cidr)}
                    (catch Exception e {:error (ex-message e)}))]
    (when-let [msg (:error parsed)]
      (die msg))
    (doseq [ip ips]
      (when-not (subnet/valid-ip? ip)
        (die (str "Invalid IP address: " ip))))
    (display/print-contains-result (:info parsed) ips)))

(defn- handle-free [[parent & allocated]]
  (when (nil? parent) (die "free requires a parent CIDR and at least one allocated CIDR"))
  (when (empty? allocated) (die "free requires at least one allocated CIDR"))
  (let [free-cidrs (ops/free-space parent allocated)
        free-infos (mapv subnet/subnet-info free-cidrs)]
    (display/print-free-result parent allocated free-infos)))

(defn- handle-plan [[parent & host-strs]]
  (when (nil? parent)    (die "plan requires a parent CIDR and at least one host count"))
  (when (empty? host-strs) (die "plan requires at least one host count"))
  (let [host-counts (mapv parse-long-or-nil host-strs)
        bad-input   (some identity
                          (map (fn [raw parsed]
                                 (when (nil? parsed) raw))
                               host-strs
                               host-counts))]
    (when bad-input
      (die (str "Invalid host count: " bad-input)))
    (let [bad-count (some (fn [n] (when (< n 1) n)) host-counts)]
      (when bad-count
        (die (str "Host count must be ≥ 1, got: " bad-count)))
      (display/print-vlsm-result parent (ops/plan-vlsm parent host-counts)))))

(defn- handle-overlaps [cidrs]
  (when (< (count cidrs) 2)
    (die "overlaps requires at least two CIDRs"))
  (display/print-overlaps-result cidrs (ops/find-overlaps cidrs)))

(defn- handle-lpm [rest-args]
  (let [routes (filterv #(str/includes? % "/") rest-args)
        ips    (filterv #(not (str/includes? % "/")) rest-args)]
    (when (empty? routes) (die "lpm requires at least one route (CIDR with /)"))
    (when (empty? ips)    (die "lpm requires at least one IP to look up"))
    (doseq [ip ips]
      (when-not (subnet/valid-ip? ip) (die (str "Invalid IP address: " ip))))
    (let [results (mapv (fn [ip]
                          (let [match      (ops/longest-prefix-match ip routes)
                                prefix-str (when match
                                             (str "/" (:prefix (subnet/parse-cidr match))))]
                            {:ip ip :match match :prefix-str prefix-str}))
                        ips)]
      (display/print-lpm-result routes results))))

(defn- handle-diff [before after]
  (when (empty? before) (die "diff requires CIDRs before '--'"))
  (when (nil? after)    (die "diff requires a '--' separator between the two CIDR sets"))
  (when (empty? after)  (die "diff requires at least one CIDR after '--'"))
  (let [{:keys [added removed unchanged]} (ops/cidr-diff before after)
        sorted-entries (->> (concat (map #(vector :removed   %) removed)
                                    (map #(vector :unchanged %) unchanged)
                                    (map #(vector :added     %) added))
                            (sort-by (fn [[_ c]] (first (subnet/cidr->range c))))
                            vec)]
    (display/print-diff-result before after added removed unchanged sorted-entries)))

(defn- handle-classify [inputs]
  (when (empty? inputs)
    (die "classify requires at least one IP or CIDR"))
  (let [classifications (mapv classify/classify inputs)]
    (display/print-classify-result classifications)))

(defn- emit-range-result! [start-ip start-n end-n]
  (when (> start-n end-n) (die "Start IP must be ≤ end IP"))
  (when (> end-n 0xFFFFFFFF) (die "End address exceeds 255.255.255.255"))
  (display/print-range-result start-ip (ip/long->ip end-n)
                              (subnet/range->cidrs start-n end-n)))

(defn- handle-range [[start-ip end-arg]]
  (when (nil? start-ip) (die "range requires a start IP and an end IP or +count"))
  (when-not (subnet/valid-ip? start-ip) (die (str "Invalid IP: " start-ip)))
  (when (nil? end-arg) (die "range requires both a start IP and an end IP or +count"))
  (let [start-n (ip/ip->long start-ip)]
    (if (str/starts-with? end-arg "+")
      (let [cnt (parse-long-or-nil (subs end-arg 1))]
        (when (nil? cnt) (die "Count must be a positive integer"))
        (when (< cnt 1)  (die "Count must be ≥ 1"))
        (when (> cnt (- 0x100000000 start-n)) (die "Count exceeds available address space"))
        (emit-range-result! start-ip start-n (+ start-n cnt -1)))
      (do
        (when-not (subnet/valid-ip? end-arg) (die (str "Invalid IP: " end-arg)))
        (emit-range-result! start-ip start-n (ip/ip->long end-arg))))))

(defn- handle-util [[parent & allocs]]
  (when-not parent (die "util requires a parent CIDR and at least one allocated CIDR"))
  (when (empty? allocs) (die "util requires at least one allocated CIDR"))
  (try (subnet/parse-cidr parent) (catch Exception e (die (ex-message e))))
  (doseq [a allocs]
    (try (subnet/parse-cidr a) (catch Exception e (die (ex-message e)))))
  (display/print-util-result (ops/utilization-info parent allocs)))

(defn- handle-analyze [args]
  (let [text (cond
               (empty? args)
               (slurp *in*)
               (= 1 (count args))
               (try (slurp (first args))
                    (catch Exception e (die (ex-message e))))
               :else
               (die "Usage: snetc analyze [<file>]"))]
    (let [routes (ops/parse-routes text)]
      (when (empty? routes)
        (die "No valid CIDR routes found in input"))
      (display/print-analyze-result (ops/analyze-routes routes)))))

(defn- handle-interactive-tree [[parent & extra]]
  (when (nil? parent) (die "tree requires a parent CIDR"))
  (when (seq extra) (die "tree accepts exactly one parent CIDR"))
  (tui/run-tree! parent))

(def ^:private subcommands
  {"aggregate" handle-aggregate
   "contains"  handle-contains
   "free"      handle-free
   "plan"      handle-plan
   "overlaps"  handle-overlaps
   "lpm"       handle-lpm
   "diff"      handle-diff
   "classify"  handle-classify
   "range"     handle-range
   "tree"      handle-interactive-tree
   "util"      handle-util
   "analyze"   handle-analyze})

(defn -main [& args]
  (let [argv     (vec args)
        sep-idx  (index-of argv "--")
        pre-args (if (not= sep-idx -1) (subvec argv 0 sep-idx) argv)
        diff-rhs (when (>= sep-idx 0) (subvec argv (inc sep-idx)))
        {:keys [options arguments errors summary]} (parse-opts pre-args cli-options)
        [cmd & rest-args] arguments]
    (cond
      errors
      (do (doseq [e errors] (binding [*out* *err*] (println e)))
          (System/exit 1))

      (or (:help options) (empty? args))
      (do (println (usage summary)) (System/exit 0))

      (:split options)
      (do (when (nil? cmd) (die "usage: snetc <cidr> --split <prefix>"))
          (try (handle-split cmd (:split options))
               (catch Exception e (die (ex-message e)))))

      (:tree options)
      (do (when (nil? cmd) (die "usage: snetc <cidr> --tree <prefix>"))
          (try (handle-tree cmd (:tree options))
               (catch Exception e (die (ex-message e)))))

      (and (nil? (subcommands cmd)) (str/includes? (str cmd) "/"))
      (try (handle-info cmd)
           (catch Exception e (die (ex-message e))))

      :else
      (if-let [handler (subcommands cmd)]
        (try (if (= cmd "diff")
               (handler rest-args diff-rhs)
               (handler rest-args))
             (catch Exception e (die (ex-message e))))
        (do (println (usage summary)) (System/exit 1))))))
