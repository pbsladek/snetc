(ns snetc.core
  "CLI entry point for snetc. Thin dispatcher — all logic lives in sub-namespaces."
  (:require [clojure.string    :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [snetc.ip          :as ip]
            [snetc.subnet      :as subnet]
            [snetc.ops         :as ops]
            [snetc.display     :as display])
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
             "  range <start> <end|+count>   Convert IP range to minimal CIDRs"]))

(defn- die [msg]
  (binding [*out* *err*]
    (println msg))
  (System/exit 1))

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

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options :in-order true)
        [cmd & rest-args] arguments]
    (cond
      errors
      (do (doseq [e errors] (binding [*out* *err*] (println e)))
          (System/exit 1))

      (or (:help options) (empty? args))
      (do (println (usage summary)) (System/exit 0))

      (:split options)
      (try (handle-split cmd (:split options))
           (catch Exception e (die (ex-message e))))

      (:tree options)
      (try (handle-tree cmd (:tree options))
           (catch Exception e (die (ex-message e))))

      (and (nil? (subcommands cmd)) (str/includes? (str cmd) "/"))
      (try (handle-info cmd)
           (catch Exception e (die (ex-message e))))

      :else
      (if-let [handler (subcommands cmd)]
        (try (handler rest-args)
             (catch Exception e (die (ex-message e))))
        (do (println (usage summary)) (System/exit 1))))))
