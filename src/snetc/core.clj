(ns snetc.core
  "CLI entry point for snetc. Thin dispatcher — all logic lives in sub-namespaces."
  (:require [clojure.string    :as str]
            [clojure.data.json :as json]
            [clojure.tools.cli :refer [parse-opts]]
            [snetc.addr        :as addr]
            [snetc.ip          :as ip]
            [snetc.subnet      :as subnet]
            [snetc.ops         :as ops]
            [snetc.classify    :as classify]
            [snetc.display     :as display]
            [snetc.tui         :as tui])
  (:gen-class))

;; When true all handlers emit JSON instead of human-readable tables.
(def ^:dynamic *json?* false)

;; Override points for die/exit-empty! — rebound in batch mode so that
;; per-command failures throw instead of terminating the whole process.
(def ^:dynamic *die-fn*
  (fn [msg]
    (binding [*out* *err*] (println msg))
    (System/exit 1)))

(def ^:dynamic *exit-empty-fn*
  (fn [] (System/exit 2)))

(def ^:private cli-options
  [[nil  "--split PREFIX" "List all /PREFIX IPv4 subnets within CIDR"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 % 32) "Prefix must be 0–32"]]
   [nil  "--tree PREFIX"  "Show IPv4 subnet split tree down to /PREFIX"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 % 32) "Prefix must be 0–32"]]
   [nil  "--json"         "Output as JSON (works with all subcommands)"]
   [nil  "--short"        "Output info as a single terse line"]
   ["-h" "--help"         "Print this help and exit"]])

(defn- usage [summary]
  (str/join \newline
            ["snetc – IP subnet calculator"
             ""
             "Usage:"
             "  snetc <cidr> [--json|--short]"
             "  snetc <subcommand> [args...] [--json]"
             ""
             "Options:"
             summary
             ""
             "Subcommands:"
             "  aggregate <cidr> [...]        Aggregate CIDRs to minimal covering set"
             "  aggregate                     Read CIDRs from stdin (one per line)"
             "  allocate <parent> <hosts|/prefix> [...used]  Next available IPv4 host block or IPv6 prefix"
             "  batch                         Run JSON command array from stdin"
             "  classify <ip-or-cidr> ...     RFC classification of IPs/CIDRs (or stdin)"
             "  contains <cidr> <ip> [...]    Check which IPs fall within a subnet (IPs from stdin)"
             "  diff <cidr> ... -- <cidr>     Diff two sets of CIDRs"
             "  free <parent> <alloc> [...]   Show unallocated space (allocs from stdin)"
             "  info <cidr>                   Show subnet info (same as snetc <cidr>)"
             "  lpm <cidr|ip> ...             Longest-prefix match"
             "  mask <mask|/prefix|n> [...]   Convert IPv4 mask and prefix notation"
             "  next <cidr> [n]               Next (or Nth) adjacent IPv4 block of same size"
             "  overlaps <cidr> [...]         Detect overlapping/contained networks (or stdin)"
             "  plan <parent> <n|/prefix> [...]  IPv4 VLSM by hosts, IPv6 allocation by prefix"
             "  prev <cidr> [n]               Previous adjacent IPv4 block of same size"
             "  range <start> <end|+count>    Convert IP range to minimal CIDRs"
             "  supernet <cidr> [...]         Smallest CIDR covering all inputs (or stdin)"
             "  tree <cidr>                   Interactive IPv4 split/join subnet planner"
             "  util <parent> <alloc> [...]   Visualise address space utilisation"
             "  analyze [<file>]              Analyse route table (or stdin)"
             "  validate <ip-or-cidr> ...     Validate IPs/CIDRs, one result per input (or stdin)"]))

(defn- die [msg] (*die-fn* msg))

(defn- exit-empty!
  "Exit code 2: ran successfully but produced no results."
  []
  (*exit-empty-fn*))

(defn- parse-long-or-nil [s]
  (try (Long/parseLong s) (catch Exception _ nil)))

(defn- parse-bigint-or-nil [s]
  (try (bigint (java.math.BigInteger. s)) (catch Exception _ nil)))

(defn- parse-prefix-token [s]
  (when (str/starts-with? (str s) "/")
    (parse-long-or-nil (subs s 1))))

(defn- index-of [xs needle]
  (or (first (keep-indexed (fn [idx x] (when (= needle x) idx)) xs))
      -1))

(defn- read-stdin-lines
  "Reads non-blank trimmed lines from stdin as a vector."
  []
  (->> (line-seq (java.io.BufferedReader. *in*))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- parse-cidr-result [cidr]
  (try {:input cidr :parsed (addr/parse-cidr cidr)}
       (catch Exception e {:input cidr :error (ex-message e)})))

(defn- parse-ip-result [ip]
  (try {:input ip :parsed (addr/parse-ip ip)}
       (catch Exception e {:input ip :error (ex-message e)})))

(defn- ensure-parsed! [parsed]
  (when-let [bad (first (filter :error parsed))]
    (die (:error bad))))

(defn- ensure-same-family! [context parsed]
  (when (seq parsed)
    (let [family (:family (:parsed (first parsed)))]
      (when-let [mixed (first (filter #(not= family (:family (:parsed %))) parsed))]
        (die (str context " requires a single address family; mixed "
                  (name family) " and " (name (:family (:parsed mixed))) " inputs"))))))

(defn- parse-cidrs-or-die! [context cidrs]
  (let [parsed (mapv parse-cidr-result cidrs)]
    (ensure-parsed! parsed)
    (ensure-same-family! context parsed)
    parsed))

(defn- parse-ips-or-die! [context ips]
  (let [parsed (mapv parse-ip-result ips)]
    (ensure-parsed! parsed)
    (ensure-same-family! context parsed)
    parsed))

(defn- ensure-compatible-families! [context left-parsed right-parsed]
  (let [items (vec (concat left-parsed right-parsed))]
    (ensure-same-family! context items)))

(defn- json-count [family n]
  (if (= :ipv6 family) (str n) n))

(defn- classful-prefix
  "Returns the classful prefix (8, 16, or 24) for an IP string, or nil for class D/E/0.x."
  [ip-str]
  (let [first-octet (try (Long/parseLong (first (str/split ip-str #"\." 2)))
                         (catch Exception _ nil))]
    (cond
      (nil? first-octet)        nil
      (<= 1   first-octet 127)  8
      (<= 128 first-octet 191)  16
      (<= 192 first-octet 223)  24
      :else                     nil)))

(defn- info->json
  "Converts a subnet-info map to a JSON-friendly map with snake_case keys."
  [info]
  (case (:family info)
    :ipv6
    {:family        "ipv6"
     :cidr          (:cidr info)
     :network       (:network info)
     :first_address (:first-address info)
     :last_address  (:last-address info)
     :addresses     (str (:addresses info))
     :prefix        (:prefix info)}

    (cond-> {:cidr       (:cidr       info)
             :network    (:network    info)
             :first_host (:first-host info)
             :last_host  (:last-host  info)
             :hosts      (:hosts      info)
             :mask       (:mask       info)
             :wildcard   (:wildcard   info)
             :prefix     (:prefix     info)}
      (:broadcast info) (assoc :broadcast (:broadcast info)))))

(defn- allocation-summary-json [info]
  (if (= :ipv6 (:family info))
    {:cidr      (:cidr info)
     :prefix    (:prefix info)
     :addresses (str (:addresses info))}
    {:cidr  (:cidr info)
     :hosts (:hosts info)
     :mask  (:mask info)}))

(defn- utilization->json [{:keys [parent-info total-addrs used-addrs free-addrs
                                  pct-used largest-free fragmentation
                                  alloc-infos free-infos]}]
  (let [family (:family parent-info)]
    (cond-> {:parent          (:cidr parent-info)
             :total_addresses (json-count family total-addrs)
             :used_addresses  (json-count family used-addrs)
             :free_addresses  (json-count family free-addrs)
             :pct_used        pct-used
             :pct_free        (- 100 pct-used)
             :largest_free    (some-> largest-free :cidr)
             :fragmentation   fragmentation
             :allocated       (mapv allocation-summary-json alloc-infos)
             :free            (mapv allocation-summary-json free-infos)}
      (= :ipv6 family) (assoc :family "ipv6"))))

(defn- tree->json [node]
  (let [info (:info node)]
    {:cidr     (:cidr  info)
     :hosts    (:hosts info)
     :children (when (:children node)
                 (mapv tree->json (:children node)))}))

;; ── handlers ──────────────────────────────────────────────────────────────────

(defn- handle-info [cidr & {:keys [short?]}]
  (let [info (addr/subnet-info cidr)]
    (cond
      *json?* (display/print-json (info->json info))
      short?  (display/print-subnet-info-short info)
      :else   (display/print-subnet-info info))))

(defn- handle-split [cidr new-prefix]
  (let [base (:prefix (subnet/subnet-info cidr))]
    (when (< new-prefix base)
      (die (str "Split prefix /" new-prefix " is smaller than base /" base)))
    (let [subnets (subnet/split-subnets cidr new-prefix)]
      (if *json?*
        (display/print-json (mapv info->json subnets))
        (display/print-split-table subnets)))))

(defn- handle-tree-flag [cidr max-prefix]
  (let [base (:prefix (subnet/subnet-info cidr))]
    (when (< max-prefix base)
      (die (str "Max prefix /" max-prefix " is smaller than base /" base)))
    (let [tree (subnet/subnet-tree cidr max-prefix)]
      (if *json?*
        (display/print-json (tree->json tree))
        (display/print-subnet-tree tree)))))

(defn- handle-aggregate [rest-args]
  (let [cidrs (if (seq rest-args) rest-args (read-stdin-lines))]
    (when (empty? cidrs)
      (die "aggregate requires at least one CIDR (or CIDRs on stdin)"))
    (parse-cidrs-or-die! "aggregate" cidrs)
    (let [result (ops/aggregate cidrs)]
      (if *json?*
        (display/print-json {:input_count  (count cidrs)
                             :result_count (count result)
                             :result       (vec result)})
        (display/print-aggregate-result cidrs result)))))

(defn- handle-contains [[cidr & ip-args]]
  (when (nil? cidr) (die "contains requires a CIDR and at least one IP"))
  (let [ips (if (seq ip-args) ip-args (read-stdin-lines))]
    (when (empty? ips)
      (die "contains requires at least one IP (or IPs on stdin)"))
    (let [parsed (try {:info (addr/subnet-info cidr)
                       :cidr (addr/parse-cidr cidr)}
                      (catch Exception e {:error (ex-message e)}))]
      (when-let [msg (:error parsed)]
        (die msg))
      (let [info       (:info parsed)
            parsed-cidr (:cidr parsed)
            parsed-ips (mapv (fn [ip]
                               (try {:input ip :parsed (addr/parse-ip ip)}
                                    (catch Exception e
                                      {:input ip :error (ex-message e)})))
                             ips)]
        (when-let [bad-ip (first (filter :error parsed-ips))]
          (die (:error bad-ip)))
        (when-let [mixed (first (filter #(not= (:family (:parsed %)) (:family info)) parsed-ips))]
          (die (str "Mixed address families: " (:input mixed) " is not in " (:cidr info))))
        (let [results (mapv (fn [{:keys [input parsed]}]
                              (let [in?  (<= (:network parsed-cidr)
                                             (:addr parsed)
                                             (:last parsed-cidr))
                                    role (when in?
                                           (case (:family info)
                                             :ipv4 (cond
                                                     (= (:text parsed) (:network info)) "network"
                                                     (= (:text parsed) (:broadcast info)) "broadcast"
                                                     :else "host")
                                             :ipv6 (when (= (:addr parsed) (:network parsed-cidr))
                                                     "network")))]
                                {:ip input :match in? :role role}))
                            parsed-ips)]
        (if *json?*
          (display/print-json {:subnet (:cidr info) :results results})
          (display/print-contains-result info results))
        (when (not-any? :match results)
          (exit-empty!)))))))

(defn- handle-free [[parent & alloc-args]]
  (when (nil? parent) (die "free requires a parent CIDR"))
  (let [allocated  (if (seq alloc-args) alloc-args (read-stdin-lines))
        parsed     (parse-cidrs-or-die! "free" (cons parent allocated))
        free-cidrs (ops/free-space parent allocated)
        free-infos (mapv addr/subnet-info free-cidrs)
        parent-info (addr/subnet-info parent)]
    (ensure-compatible-families! "free" [(first parsed)] (rest parsed))
    (if *json?*
      (display/print-json
       (cond-> {:parent          (:cidr parent-info)
                :allocated_count (count allocated)
                :free_count      (count free-infos)
                :free            (mapv allocation-summary-json free-infos)}
         (= :ipv6 (:family parent-info)) (assoc :family "ipv6")))
      (display/print-free-result parent allocated free-infos))
    (when (empty? free-infos)
      (exit-empty!))))

(defn- handle-plan [[parent & host-strs]]
  (when (nil? parent)      (die "plan requires a parent CIDR and at least one host count"))
  (when (empty? host-strs) (die "plan requires at least one host count"))
  (let [parent-result (parse-cidr-result parent)]
    (when-let [msg (:error parent-result)]
      (die msg))
    (if (= :ipv6 (:family (:parsed parent-result)))
      (let [prefixes  (mapv parse-prefix-token host-strs)
            bad-input (some identity
                            (map (fn [raw parsed] (when (nil? parsed) raw))
                                 host-strs prefixes))]
        (when bad-input
          (die (str "IPv6 plan requires prefix requests such as /64, got: " bad-input)))
        (when-let [bad-prefix (some (fn [p]
                                      (when-not (addr/valid-prefix? :ipv6 p) p))
                                    prefixes)]
          (die (str "Prefix must be 0-128, got: " bad-prefix)))
        (let [base-prefix (:prefix (:parsed parent-result))]
          (when-let [too-wide (some (fn [p] (when (< p base-prefix) p)) prefixes)]
            (die (str "Requested prefix /" too-wide
                      " is smaller than parent /" base-prefix))))
        (let [allocs (ops/plan-prefixes parent prefixes)]
          (if *json?*
            (display/print-json
             {:family      "ipv6"
              :parent      (:cidr (addr/subnet-info parent))
              :allocations (mapv (fn [{:keys [info requested requested-prefix]}]
                                   (assoc (info->json info)
                                          :requested requested
                                          :requested_prefix requested-prefix))
                                 allocs)})
            (display/print-vlsm-result parent allocs))))
      (let [host-counts (mapv parse-long-or-nil host-strs)
            bad-input   (some identity
                              (map (fn [raw parsed] (when (nil? parsed) raw))
                                   host-strs host-counts))]
        (when bad-input (die (str "Invalid host count: " bad-input)))
        (let [bad-count (some (fn [n] (when (< n 1) n)) host-counts)]
          (when bad-count (die (str "Host count must be ≥ 1, got: " bad-count)))
          (let [allocs (ops/plan-vlsm parent host-counts)]
            (if *json?*
              (display/print-json
               {:parent      parent
                :allocations (mapv (fn [{:keys [info requested]}]
                                     (assoc (info->json info) :requested requested))
                                   allocs)})
              (display/print-vlsm-result parent allocs))))))))

(defn- handle-overlaps [cidr-args]
  (let [cidrs (if (seq cidr-args) cidr-args (read-stdin-lines))]
    (when (< (count cidrs) 2)
      (die "overlaps requires at least two CIDRs"))
    (parse-cidrs-or-die! "overlaps" cidrs)
    (let [overlaps (ops/find-overlaps cidrs)]
      (if *json?*
        (display/print-json {:checked       (count cidrs)
                             :overlap_count (count overlaps)
                             :overlaps      (mapv (fn [{:keys [a b type]}]
                                                    {:a a :b b :type (name type)})
                                                  overlaps)})
        (display/print-overlaps-result cidrs overlaps))
      (when (empty? overlaps)
        (exit-empty!)))))

(defn- handle-lpm [rest-args]
  (let [routes (filterv #(str/includes? % "/") rest-args)
        ips    (filterv #(not (str/includes? % "/")) rest-args)]
    (when (empty? routes) (die "lpm requires at least one route (CIDR with /)"))
    (when (empty? ips)    (die "lpm requires at least one IP to look up"))
    (let [parsed-routes (parse-cidrs-or-die! "lpm" routes)
          parsed-ips    (parse-ips-or-die! "lpm" ips)]
      (ensure-compatible-families! "lpm" parsed-routes parsed-ips))
    (let [results (mapv (fn [ip]
                          (let [match      (ops/longest-prefix-match ip routes)
                                prefix-str (when match
                                             (str "/" (:prefix (addr/parse-cidr match))))]
                            {:ip ip :match match :prefix-str prefix-str}))
                        ips)]
      (if *json?*
        (display/print-json
         {:routes  routes
          :results (mapv (fn [{:keys [ip match prefix-str]}]
                           {:ip     ip
                            :match  match
                            :prefix (when prefix-str
                                      (Integer/parseInt (subs prefix-str 1)))})
                         results)})
        (display/print-lpm-result routes results))
      (when (some #(nil? (:match %)) results)
        (exit-empty!)))))

(defn- handle-diff [before after]
  (when (empty? before) (die "diff requires CIDRs before '--'"))
  (when (nil? after)    (die "diff requires a '--' separator between the two CIDR sets"))
  (when (empty? after)  (die "diff requires at least one CIDR after '--'"))
  (let [parsed-before (parse-cidrs-or-die! "diff" before)
        parsed-after  (parse-cidrs-or-die! "diff" after)]
    (ensure-compatible-families! "diff" parsed-before parsed-after))
  (let [{:keys [added removed unchanged]} (ops/cidr-diff before after)
        sorted-entries (->> (concat (map #(vector :removed   %) removed)
                                    (map #(vector :unchanged %) unchanged)
                                    (map #(vector :added     %) added))
                            (sort-by (fn [[_ c]] (:start (addr/cidr->range c))))
                            vec)]
    (if *json?*
      (display/print-json {:added added :removed removed :unchanged unchanged})
      (display/print-diff-result before after added removed unchanged sorted-entries))
    (when (and (empty? added) (empty? removed))
      (exit-empty!))))

(defn- handle-classify [inputs]
  (let [inputs (if (seq inputs) inputs (read-stdin-lines))]
    (when (empty? inputs)
      (die "classify requires at least one IP or CIDR (or from stdin)"))
    (let [classifications (mapv classify/classify inputs)]
      (if *json?*
        (display/print-json
         (mapv (fn [c]
                 (cond-> {:input    (:input    c)
                          :category (display/category-label c)
                          :rfc      (:rfc      c)
                          :routable (:routable? c)
                          :spans    (:spans?   c)}
                   (= :ipv6 (:family c)) (assoc :family "ipv6")))
               classifications))
        (display/print-classify-result classifications)))))

(defn- emit-range-result! [family start-ip start-n end-n]
  (when (> start-n end-n) (die "Start IP must be ≤ end IP"))
  (let [space (addr/address-count family 0)]
    (when (>= end-n space)
      (die (str "End address exceeds " (addr/address->text family (dec space))))))
  (let [end-ip (addr/address->text family end-n)
        total  (inc (- end-n start-n))
        cidrs  (addr/range->cidrs family start-n end-n)]
    (if *json?*
      (display/print-json (cond-> {:start      start-ip
                                   :end        end-ip
                                   :total      (json-count family total)
                                   :cidr_count (count cidrs)
                                   :cidrs      cidrs}
                            (= :ipv6 family) (assoc :family "ipv6")))
      (display/print-range-result start-ip end-ip total cidrs))))

(defn- handle-range [[start-ip end-arg]]
  (when (nil? start-ip) (die "range requires a start IP and an end IP or +count"))
  (when (nil? end-arg) (die "range requires both a start IP and an end IP or +count"))
  (let [start-result (parse-ip-result start-ip)]
    (when-let [msg (:error start-result)]
      (die msg))
    (let [parsed-start (:parsed start-result)
          family       (:family parsed-start)
          start-n      (:addr parsed-start)]
    (if (str/starts-with? end-arg "+")
      (let [cnt (parse-bigint-or-nil (subs end-arg 1))]
        (when (nil? cnt) (die "Count must be a positive integer"))
        (when (< cnt 1)  (die "Count must be ≥ 1"))
        (when (> cnt (- (addr/address-count family 0) start-n)) (die "Count exceeds available address space"))
        (emit-range-result! family (:text parsed-start) start-n (+ start-n cnt -1)))
      (let [end-result (parse-ip-result end-arg)]
        (when-let [msg (:error end-result)]
          (die msg))
        (let [parsed-end (:parsed end-result)]
          (when (not= family (:family parsed-end))
            (die "range requires start and end addresses from the same family"))
          (emit-range-result! family (:text parsed-start) start-n (:addr parsed-end))))))))

(defn- handle-util [[parent & allocs]]
  (when-not parent (die "util requires a parent CIDR and at least one allocated CIDR"))
  (when (empty? allocs) (die "util requires at least one allocated CIDR"))
  (parse-cidrs-or-die! "util" (cons parent allocs))
  (let [result (ops/utilization-info parent allocs)]
    (if *json?*
      (display/print-json (utilization->json result))
      (display/print-util-result result))))

(defn- parse-mask-input
  "Accepts dotted mask (255.255.0.0), plain integer (16), or slash-prefix (/16).
  Returns prefix length or nil on failure."
  [s]
  (cond
    (str/starts-with? s "/")
    (let [n (parse-long-or-nil (subs s 1))]
      (when (and n (<= 0 n 32)) n))
    (str/includes? s ".")
    (try (ip/mask->prefix (ip/ip->long s)) (catch Exception _ nil))
    :else
    (let [n (parse-long-or-nil s)]
      (when (and n (<= 0 n 32)) n))))

(defn- handle-adjacent [args direction]
  (let [[cidr n-str] args
        n-raw        (if (nil? n-str) 1 (parse-long-or-nil n-str))]
    (when (nil? cidr)          (die (str direction " requires a CIDR")))
    (when (nil? n-raw)         (die (str "Invalid step count: " n-str)))
    (when (< n-raw 1)          (die "Step count must be ≥ 1"))
    (when (> n-raw 0xFFFFFFFF) (die "Step count exceeds IPv4 address space"))
    (when (> (count args) 2)   (die (str direction " accepts at most one CIDR and one step count")))
    (let [n      (if (= direction "prev") (- n-raw) n-raw)
          result (subnet/adjacent-cidr cidr n)]
      (if *json?*
        (display/print-json {:input direction :direction direction :n n-raw :result result})
        (display/print-adjacent-result cidr direction n-raw result)))))

(defn- handle-mask [inputs]
  (when (empty? inputs)
    (die "mask requires at least one argument (dotted mask, /prefix, or prefix number)"))
  (let [conversions
        (mapv (fn [s]
                (let [prefix (parse-mask-input s)]
                  (when (nil? prefix) (die (str "Cannot parse mask input: " s)))
                  {:input    s
                   :prefix   prefix
                   :mask     (ip/long->ip (ip/prefix->mask prefix))
                   :wildcard (ip/long->ip (ip/wildcard-mask prefix))}))
              inputs)]
    (if *json?*
      (display/print-json conversions)
      (display/print-mask-result conversions))))

(defn- handle-supernet [cidr-args]
  (let [cidrs (if (seq cidr-args) cidr-args (read-stdin-lines))]
    (when (< (count cidrs) 2)
      (die "supernet requires at least two CIDRs"))
    (parse-cidrs-or-die! "supernet" cidrs)
    (let [result (ops/supernet cidrs)]
      (if *json?*
        (display/print-json {:input (vec cidrs) :result result})
        (display/print-supernet-result cidrs result)))))

(defn- handle-analyze [args]
  (when (> (count args) 1)
    (die "Usage: snetc analyze [<file>]"))
  (let [text (cond
               (empty? args)
               (slurp *in*)
               :else
               (try (slurp (first args))
                    (catch Exception e (die (ex-message e)))))
        routes (ops/parse-routes text)]
    (when (empty? routes)
      (die "No valid CIDR routes found in input"))
    (parse-cidrs-or-die! "analyze" routes)
    (let [analysis (ops/analyze-routes routes)]
      (if *json?*
        (display/print-json
         (cond-> {:route_count      (:route-count      analysis)
                  :aggregated_count (:aggregated-count analysis)
                  :savings          (:savings          analysis)
                  :groups           (mapv (fn [{:keys [summary routes]}]
                                            {:summary summary :routes routes})
                                          (:groups analysis))
                  :contained        (mapv (fn [{:keys [a b type]}]
                                            (if (= type :a-contains-b)
                                              {:container a :contained b}
                                              {:container b :contained a}))
                                          (:contained analysis))}
           (= :ipv6 (:family analysis)) (assoc :family "ipv6")))
        (display/print-analyze-result analysis))
      (when (and (zero? (:savings analysis))
                 (empty? (:contained analysis)))
        (exit-empty!)))))

(defn- handle-allocate [[parent hosts-str & used]]
  (when (nil? parent)    (die "allocate requires a parent CIDR and a host count"))
  (when (nil? hosts-str) (die "allocate requires a host count"))
  (let [parent-result (parse-cidr-result parent)]
    (when-let [msg (:error parent-result)]
      (die msg))
    (if (= :ipv6 (:family (:parsed parent-result)))
      (let [requested-prefix (parse-prefix-token hosts-str)]
        (when (nil? requested-prefix)
          (die (str "IPv6 allocate requires a prefix request such as /64, got: " hosts-str)))
        (when-not (addr/valid-prefix? :ipv6 requested-prefix)
          (die (str "Prefix must be 0-128, got: " requested-prefix)))
        (when (< requested-prefix (:prefix (:parsed parent-result)))
          (die (str "Requested prefix /" requested-prefix
                    " is smaller than parent /" (:prefix (:parsed parent-result)))))
        (parse-cidrs-or-die! "allocate" (cons parent used))
        (let [result (ops/next-available-prefix parent (vec used) requested-prefix)]
          (if result
            (let [info (addr/subnet-info result)]
              (if *json?*
                (display/print-json (assoc (info->json info)
                                           :parent (:cidr (addr/subnet-info parent))
                                           :used (vec used)
                                           :requested (str "/" requested-prefix)
                                           :requested_prefix requested-prefix))
                (display/print-allocate-result parent (vec used) (str "/" requested-prefix) info)))
            (die (str "No available /" requested-prefix " block in " parent)))))
      (do
        (when (str/includes? (str hosts-str) "/")
          (die "allocate: second argument must be a host count, not a CIDR"))
        (let [n (parse-long-or-nil hosts-str)]
          (when (nil? n) (die (str "Invalid host count: " hosts-str)))
          (when (< n 1)  (die "Host count must be ≥ 1"))
          (try (subnet/parse-cidr parent) (catch Exception e (die (ex-message e))))
          (doseq [c used]
            (try (subnet/parse-cidr c) (catch Exception e (die (ex-message e)))))
          (let [result (ops/next-available parent (vec used) n)]
            (if result
              (let [info (subnet/subnet-info result)]
                (if *json?*
                  (display/print-json (assoc (info->json info)
                                             :parent    parent
                                             :used      (vec used)
                                             :requested n))
                  (display/print-allocate-result parent (vec used) n info)))
              (die (str "No available block for " n " hosts in " parent)))))))))

(defn- handle-validate [inputs]
  (let [inputs (if (seq inputs) inputs (read-stdin-lines))]
    (when (empty? inputs)
      (die "validate requires at least one IP or CIDR (or from stdin)"))
    (let [results
          (mapv (fn [s]
                  (if (str/includes? s "/")
                    (try (let [parsed (addr/parse-cidr s)]
                           {:input s :valid true :type (str (name (:family parsed)) "-cidr") :error nil})
                         (catch Exception e
                           {:input s
                            :valid false
                            :type  (if (str/includes? s ":") "ipv6-cidr" "ipv4-cidr")
                            :error (ex-message e)}))
                    (try (let [parsed (addr/parse-ip s)]
                           {:input s :valid true :type (name (:family parsed)) :error nil})
                         (catch Exception e
                           {:input s
                            :valid false
                            :type  (if (str/includes? s ":") "ipv6" "ipv4")
                            :error (ex-message e)}))))
                inputs)]
      (if *json?*
        (display/print-json results)
        (display/print-validate-result results))
      (when (some #(not (:valid %)) results)
        (exit-empty!)))))

(defn- handle-interactive-tree [[parent & extra]]
  (when (nil? parent) (die "tree requires a parent CIDR"))
  (when (seq extra) (die "tree accepts exactly one parent CIDR"))
  (when (str/includes? parent ":")
    (die "tree is IPv4-only; non-interactive commands support IPv6"))
  (tui/run-tree! parent))

;; ── subcommand table ───────────────────────────────────────────────────────────

(def ^:private subcommands
  {"aggregate" handle-aggregate
   "allocate"  handle-allocate
   "classify"  handle-classify
   "contains"  handle-contains
   "diff"      handle-diff
   "free"      handle-free
   "info"      (fn [[cidr & _]]
                 (when (nil? cidr) (die "info requires a CIDR"))
                 (handle-info cidr))
   "lpm"       handle-lpm
   "mask"      handle-mask
   "next"      #(handle-adjacent % "next")
   "overlaps"  handle-overlaps
   "plan"      handle-plan
   "prev"      #(handle-adjacent % "prev")
   "range"     handle-range
   "supernet"  handle-supernet
   "tree"      handle-interactive-tree
   "util"      handle-util
   "analyze"   handle-analyze
   "validate"  handle-validate})

;; ── batch mode ────────────────────────────────────────────────────────────────

(defn- execute-command!
  "Routes a single command by name with args vec. Used by batch mode.
  Throws ex-info on unknown command."
  [cmd args]
  (when (= cmd "tree")
    (die "tree is an interactive command and cannot be used in batch mode"))
  (if-let [handler (subcommands cmd)]
    (if (= cmd "diff")
      (let [sep-idx (index-of args "--")
            before  (if (not= sep-idx -1) (subvec args 0 sep-idx) args)
            after   (when (>= sep-idx 0) (subvec args (inc sep-idx)))]
        (handler before after))
      (handler args))
    (throw (ex-info (str "Unknown command: " cmd) {:batch/exit 1}))))

(defn- run-batch-item!
  "Executes one batch item in JSON mode, capturing stdout via StringWriter.
  Returns {:cmd exit :result} or {:cmd :exit :error}."
  [cmd args]
  (let [sw         (java.io.StringWriter.)
        exit-code  (atom 0)
        error-msg  (atom nil)]
    (try
      (binding [*out*          sw
                *json?*        true
                *die-fn*       (fn [msg]
                                 (reset! exit-code 1)
                                 (reset! error-msg msg)
                                 (throw (ex-info msg {:batch/exit 1})))
                *exit-empty-fn* (fn []
                                  (reset! exit-code 2)
                                  (throw (ex-info "" {:batch/exit 2})))]
        (execute-command! cmd args))
      (catch clojure.lang.ExceptionInfo e
        (when (zero? @exit-code)
          (reset! exit-code 1)
          (reset! error-msg (ex-message e))))
      (catch Exception e
        (reset! exit-code 1)
        (reset! error-msg (ex-message e))))
    (let [captured (str sw)
          parsed   (when (seq captured)
                     (try (json/read-str captured)
                          (catch Exception _ nil)))]
      (cond-> {:cmd cmd :exit @exit-code}
        parsed     (assoc :result parsed)
        @error-msg (assoc :error @error-msg)))))

(defn- handle-batch [_args]
  (let [raw      (try (slurp *in*)
                      (catch Exception _ (die "batch: could not read stdin")))
        commands (try (json/read-str raw)
                      (catch Exception _ (die "batch: stdin is not valid JSON")))]
    (when-not (sequential? commands)
      (die "batch: stdin must be a JSON array of {\"cmd\":\"...\",\"args\":[...]} objects"))
    (display/print-json
     (mapv (fn [item]
             (let [cmd  (get item "cmd")
                   args (vec (get item "args" []))]
               (if (nil? cmd)
                 {:cmd nil :exit 1 :error "missing \"cmd\" field"}
                 (run-batch-item! cmd args))))
           commands))))

;; ── main entry point ──────────────────────────────────────────────────────────

(def ^:private subcommands-with-batch
  (assoc subcommands "batch" handle-batch))

(defn -main [& args]
  (let [argv           (vec args)
        sep-idx        (index-of argv "--")
        pre-args       (if (not= sep-idx -1) (subvec argv 0 sep-idx) argv)
        raw-rhs        (when (>= sep-idx 0) (subvec argv (inc sep-idx)))
        trailing-json? (boolean (some #{"--json"} (or raw-rhs [])))
        diff-rhs       (when raw-rhs (filterv #(not= "--json" %) raw-rhs))
        {:keys [options arguments errors summary]} (parse-opts pre-args cli-options)
        [cmd & rest-args] arguments]
    (cond
      errors
      (do (doseq [e errors] (binding [*out* *err*] (println e)))
          (System/exit 1))

      (or (:help options) (empty? args))
      (do (println (usage summary)) (System/exit 0))

      :else
      (binding [*json?* (boolean (or (:json options) trailing-json?))]
        (cond
          (:split options)
          (do (when (nil? cmd) (die "usage: snetc <cidr> --split <prefix>"))
              (try (handle-split cmd (:split options))
                   (catch Exception e (die (ex-message e)))))

          (:tree options)
          (do (when (nil? cmd) (die "usage: snetc <cidr> --tree <prefix>"))
              (try (handle-tree-flag cmd (:tree options))
                   (catch Exception e (die (ex-message e)))))

          (and (nil? (subcommands-with-batch cmd)) (str/includes? (str cmd) "/"))
          (try (handle-info cmd :short? (:short options))
               (catch Exception e (die (ex-message e))))

          (and (nil? (subcommands-with-batch cmd)) (subnet/valid-ip? (str cmd)))
          (let [prefix (classful-prefix cmd)]
            (if prefix
              (let [net-addr (ip/long->ip (ip/network-addr (ip/ip->long cmd) prefix))
                    cidr     (str net-addr "/" prefix)]
                (when-not (or (:short options) *json?*)
                  (println (str "# inferred /" prefix " (classful) from " cmd)))
                (try (handle-info cidr :short? (:short options))
                     (catch Exception e (die (ex-message e)))))
              (die (str "Cannot infer classful prefix for " cmd " (class D/E or 0.x)"))))

          :else
          (if-let [handler (subcommands-with-batch cmd)]
            (try (if (= cmd "diff")
                   (handler rest-args diff-rhs)
                   (handler rest-args))
                 (catch Exception e (die (ex-message e))))
            (do (println (usage summary)) (System/exit 1))))))))
