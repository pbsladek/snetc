(ns snetc.display
  "Terminal display: all human-readable output for snetc commands."
  (:require [clojure.string   :as str]
            [clojure.data.json :as json]))

(defn- label-row [label value]
  (format "  %-18s %s" (str label ":") value))

(defn print-subnet-info
  "Prints a formatted summary of info to stdout."
  [info]
  (println (str "\n" (:cidr info)))
  (println (label-row "Network"       (:network    info)))
  (if (= :ipv6 (:family info))
    (do
      (println (label-row "First Address" (:first-address info)))
      (println (label-row "Last Address"  (:last-address  info)))
      (println (label-row "Addresses"     (:addresses     info))))
    (do
      (when (:broadcast info)
        (println (label-row "Broadcast"   (:broadcast  info))))
      (println (label-row "First Host"    (:first-host info)))
      (println (label-row "Last Host"     (:last-host  info)))
      (println (label-row "Hosts"         (:hosts      info)))
      (println (label-row "Subnet Mask"   (:mask       info)))
      (println (label-row "Wildcard Mask" (:wildcard   info)))))
  (println))

(defn print-subnet-info-json
  "Prints subnet info as JSON to stdout."
  [info]
  (println (json/write-str
            (if (= :ipv6 (:family info))
              {:family        "ipv6"
               :cidr          (:cidr info)
               :network       (:network info)
               :first_address (:first-address info)
               :last_address  (:last-address info)
               :addresses     (str (:addresses info))
               :prefix        (:prefix info)}
              (cond-> {:cidr       (:cidr      info)
                       :network    (:network   info)
                       :first_host (:first-host info)
                       :last_host  (:last-host  info)
                       :hosts      (:hosts     info)
                       :mask       (:mask      info)
                       :wildcard   (:wildcard  info)
                       :prefix     (:prefix    info)}
                (:broadcast info) (assoc :broadcast (:broadcast info)))))))

(defn print-split-table
  "Prints a table of subnets to stdout."
  [subnets]
  (if (= :ipv6 (:family (first subnets)))
    (let [fmt "  %-43s %-39s %-39s %s"]
      (println)
      (println (format fmt "Network/CIDR" "First Address" "Last Address" "Addresses"))
      (println (apply str (repeat 135 "-")))
      (doseq [s subnets]
        (println (format fmt
                         (:cidr s)
                         (:first-address s)
                         (:last-address s)
                         (:addresses s))))
      (println))
    (let [fmt "  %-20s %-18s %-18s %-18s %-18s %s"]
      (println)
      (println (format fmt "Network/CIDR" "Subnet Mask" "First Host" "Last Host" "Broadcast" "Hosts"))
      (println (apply str (repeat 110 "-")))
      (doseq [s subnets]
        (println (format fmt
                         (:cidr       s)
                         (:mask       s)
                         (:first-host s)
                         (:last-host  s)
                         (or (:broadcast s) "-")
                         (:hosts      s))))
      (println))))

(defn- tree-summary [info]
  (if (= :ipv6 (:family info))
    (str (:addresses info) " addresses")
    (str (:hosts info) " hosts")))

(defn- tree-lines
  "Returns formatted lines for node, indented with ASCII box-drawing characters."
  [node prefix-str last?]
  (let [info     (:info node)
        children (:children node)
        branch   (if last? "└─ " "├─ ")
        summary  (str prefix-str branch (:cidr info) "  [" (tree-summary info) "]")
        child-px (str prefix-str (if last? "   " "│  "))]
    (cons summary
          (mapcat (fn [i child]
                    (tree-lines child child-px (= i (dec (count children)))))
                  (range)
                  children))))

(defn print-subnet-tree
  "Prints the subnet tree rooted at root to stdout."
  [root]
  (let [info     (:info root)
        children (:children root)]
    (println (str "\n" (:cidr info) "  [" (tree-summary info) "]"))
    (doseq [line (mapcat (fn [i child]
                           (tree-lines child "" (= i (dec (count children)))))
                         (range)
                         children)]
      (println line))
    (println)))

(defn print-aggregate-result
  "Prints aggregated CIDRs with input/output counts to stdout."
  [input-cidrs result-cidrs]
  (println (format "\nAggregated %d network(s) into %d:" (count input-cidrs) (count result-cidrs)))
  (doseq [c result-cidrs]
    (println (str "  " c)))
  (println))

(defn print-contains-result
  "Prints a containment table from pre-computed result maps."
  [info results]
  (let [cidr (:cidr info)
        fmt  "  %-20s %-6s %s"]
    (println (str "\nSubnet: " cidr "\n"))
    (println (format fmt "IP" "Match" "Note"))
    (println (apply str (repeat 50 "-")))
    (doseq [result results]
      (let [note (case (:role result)
                   (:network "network")     "(network address)"
                   (:broadcast "broadcast") "(broadcast address)"
                   nil)]
        (println (format fmt (:ip result) (if (:match result) "yes" "no") (or note "")))))
    (println)))

(defn print-free-result
  "Prints free space within parent-cidr after removing allocated-cidrs to stdout.
  result-infos is a seq of pre-computed subnet-info maps."
  [parent-cidr allocated-cidrs result-infos]
  (println (str "\nFree space in " parent-cidr
                (when (seq allocated-cidrs)
                  (str " (excluding " (count allocated-cidrs) " allocated block(s))"))
                ":\n"))
  (if (empty? result-infos)
    (println "  (none – fully allocated)\n")
    (if (= :ipv6 (:family (first result-infos)))
      (let [fmt "  %-43s %s"]
        (println (format fmt "CIDR" "Addresses"))
        (println (apply str (repeat 70 "-")))
        (doseq [info result-infos]
          (println (format fmt (:cidr info) (:addresses info))))
        (println))
      (let [fmt "  %-20s %-18s %s"]
        (println (format fmt "CIDR" "Subnet Mask" "Hosts"))
        (println (apply str (repeat 50 "-")))
        (doseq [info result-infos]
          (println (format fmt (:cidr info) (:mask info) (:hosts info))))
        (println)))))

(defn print-diff-result
  "Prints a sorted diff of before-cidrs vs after-cidrs to stdout.
  sorted-entries is a pre-sorted vec of [status cidr] pairs from the handler."
  [before-cidrs after-cidrs added removed unchanged sorted-entries]
  (println (format "\nDiff: %d → %d network(s)\n" (count before-cidrs) (count after-cidrs)))
  (doseq [[status cidr] sorted-entries]
    (println (format "  %s %s"
                     (case status :added "[+]" :removed "[-]" :unchanged "[=]")
                     cidr)))
  (println (format "\n  Added: %d  Removed: %d  Unchanged: %d\n"
                   (count added) (count removed) (count unchanged))))

(defn category-label [{:keys [name spans? bcast-name category-path]}]
  (if (seq category-path)
    (str/join " → " (map :name category-path))
    (str name (when spans? (str " → " bcast-name)))))

(defn print-classify-result
  "Prints RFC classification for each pre-computed classification map to stdout."
  [classifications]
  (let [;; Compute category column width dynamically so spanning CIDRs like
        ;; "Documentation TEST-NET-3 → Private" don't overflow into the RFC column.
        input-width (apply max 22 (map #(count (:input %)) classifications))
        cat-width   (apply max 8
                           (map #(count (category-label %)) classifications))
        fmt         (str "  %-" input-width "s %-" (+ cat-width 2) "s %-12s %s")]
    (println)
    (println (format fmt "Input" "Category" "RFC" "Routable"))
    (println (apply str (repeat (+ input-width cat-width 30) "-")))
    (doseq [{:keys [input rfc routable?] :as classification} classifications]
      (let [rfc-str (if (str/blank? rfc) "-" rfc)]
        (println (format fmt input (category-label classification) rfc-str (if routable? "yes" "no")))))
    (println)))

(defn print-range-result
  "Prints the minimal CIDR list for the range start-ip to end-ip to stdout."
  [start-ip end-ip total cidrs]
  (println (format "\nRange: %s – %s  (%s address%s)\n"
                   start-ip end-ip total (if (= 1 total) "" "es")))
  (doseq [c cidrs] (println (str "  " c)))
  (println (format "\n  %d CIDR block(s)\n" (count cidrs))))

(defn print-vlsm-result
  "Prints the VLSM allocation table for parent-cidr to stdout."
  [parent-cidr allocations]
  (if (= :ipv6 (-> allocations first :info :family))
    (let [fmt "  %-4s %-12s %-43s %s"]
      (println (str "\nIPv6 prefix plan for " parent-cidr "\n"))
      (println (format fmt "#" "Requested" "Allocated" "Addresses"))
      (println (apply str (repeat 80 "-")))
      (doseq [[idx {:keys [info requested]}] (map-indexed vector allocations)]
        (println (format fmt
                         (inc idx)
                         requested
                         (:cidr info)
                         (:addresses info))))
      (println))
    (let [fmt "  %-4s %-12s %-20s %-18s %-16s %-16s %s"]
      (println (str "\nVLSM plan for " parent-cidr "\n"))
      (println (format fmt "#" "Requested" "Allocated" "Subnet Mask" "First Host" "Last Host" "Hosts"))
      (println (apply str (repeat 105 "-")))
      (doseq [[idx {:keys [info requested]}] (map-indexed vector allocations)]
        (println (format fmt
                         (inc idx)
                         requested
                         (:cidr       info)
                         (:mask       info)
                         (:first-host info)
                         (:last-host  info)
                         (:hosts      info))))
      (println))))

(defn print-overlaps-result
  "Prints the overlap report for cidrs to stdout."
  [cidrs overlaps]
  (println (format "\nChecking %d network(s) for overlaps...\n" (count cidrs)))
  (if (empty? overlaps)
    (println "  No overlaps found.\n")
    (let [fmt "  %-22s %-22s %s"]
      (println (format fmt "CIDR A" "CIDR B" "Relationship"))
      (println (apply str (repeat 70 "-")))
      (doseq [{:keys [a b type]} overlaps]
        (println (format fmt a b
                         (case type
                           :a-contains-b "A contains B"
                           :b-contains-a "B contains A"
                           :partial      "partial overlap"))))
      (println (format "\n  %d overlap(s) found.\n" (count overlaps))))))

(defn print-util-result
  "Prints the utilization map and stats for the pre-computed result map."
  [{:keys [parent-info alloc-infos free-infos largest-free
           total-addrs used-addrs free-addrs pct-used fragmentation bar]}]
  (println (format "\n%s  [%s addresses]\n" (:cidr parent-info) total-addrs))
  (println (str "  " bar "\n"))
  (let [largest-cidr (:cidr largest-free)]
    (if (= :ipv6 (:family parent-info))
      (let [fmt "  %-11s %-43s %s addresses%s"]
        (doseq [info alloc-infos]
          (println (format fmt "Allocated" (:cidr info) (:addresses info) "")))
        (doseq [info free-infos]
          (println (format fmt "Free" (:cidr info) (:addresses info)
                           (if (= (:cidr info) largest-cidr) "  \u2190 largest" "")))))
      (let [fmt "  %-11s %-20s %-18s %d hosts%s"]
        (doseq [info alloc-infos]
          (println (format fmt "Allocated" (:cidr info) (:mask info) (:hosts info) "")))
        (doseq [info free-infos]
          (println (format fmt "Free" (:cidr info) (:mask info) (:hosts info)
                           (if (= (:cidr info) largest-cidr) "  \u2190 largest" "")))))))
  (println)
  (println (format "  Used: %s / %s  (%d%%)  |  Free: %s in %d block%s%s\n"
                   used-addrs total-addrs pct-used
                   free-addrs (count free-infos)
                   (if (= 1 (count free-infos)) "" "s")
                   (if fragmentation (str "  |  Fragmentation: " fragmentation) ""))))

(defn print-analyze-result
  "Prints route table analysis for the pre-computed result map."
  [{:keys [route-count aggregated-count savings groups contained]}]
  (if (zero? savings)
    (println (format "\n%d route%s parsed  (fully optimized, no issues found)\n"
                     route-count (if (= 1 route-count) "" "s")))
    (println (format "\n%d route%s parsed  \u2192  %d after aggregation  (saves %d)\n"
                     route-count (if (= 1 route-count) "" "s")
                     aggregated-count savings)))
  (if (empty? contained)
    (println "  No containment relationships found.")
    (do
      (println (format "  Containment (%d):" (count contained)))
      (doseq [{:keys [a b type]} contained]
        (let [[inner outer] (if (= type :a-contains-b) [b a] [a b])]
          (println (format "    %-20s \u2282  %s" inner outer))))))
  (println)
  (if (empty? groups)
    (println "  No summarization opportunities found.")
    (do
      (println (format "  Summarization opportunities (%d):" (count groups)))
      (doseq [{:keys [summary routes]} groups]
        (println)
        (let [n (count routes)]
          (doseq [[idx r] (map-indexed vector routes)]
            (let [bracket (cond (= idx 0)       "\u250c"
                                (= idx (dec n)) "\u2518"
                                :else           "\u2502")
                  suffix  (when (= idx (dec n))
                            (format "  \u2192  %s  (%d \u2192 1)" summary n))]
              (println (format "    %-20s %s%s" r bracket (or suffix "")))))))))
  (println))

(defn print-json
  "Serialises data to a single JSON line on stdout."
  [data]
  (println (json/write-str data)))

(defn print-allocate-result
  "Prints the recommended CIDR allocation to stdout."
  [parent used n info]
  (if (= :ipv6 (:family info))
    (do
      (println (format "\nAllocate %s in %s" n parent))
      (when (seq used)
        (println (format "  Excluding: %s" (str/join ", " used))))
      (println)
      (println (label-row "Allocated"     (:cidr          info)))
      (println (label-row "First Address" (:first-address info)))
      (println (label-row "Last Address"  (:last-address  info)))
      (println (label-row "Addresses"     (:addresses     info)))
      (println))
    (do
      (println (format "\nAllocate %d host(s) in %s" n parent))
      (when (seq used)
        (println (format "  Excluding: %s" (str/join ", " used))))
      (println)
      (println (label-row "Allocated"   (:cidr       info)))
      (println (label-row "First Host"  (:first-host info)))
      (println (label-row "Last Host"   (:last-host  info)))
      (println (label-row "Hosts"       (:hosts      info)))
      (println (label-row "Subnet Mask" (:mask       info)))
      (println))))

(defn print-subnet-info-short
  "Prints a single terse summary line for info to stdout."
  [info]
  (if (= :ipv6 (:family info))
    (println (format "%s  %s\u2013%s  %s addresses"
                     (:cidr info)
                     (:first-address info)
                     (:last-address info)
                     (:addresses info)))
    (println (format "%s  %s\u2013%s  %d hosts  mask %s"
                     (:cidr      info)
                     (:first-host info)
                     (:last-host  info)
                     (:hosts     info)
                     (:mask      info)))))

(defn print-adjacent-result
  "Prints an adjacent block result to stdout."
  [input-cidr direction n result-cidr]
  (println (format "\n%s  %s %s  =>  %s\n" input-cidr direction n result-cidr)))

(defn print-mask-result
  "Prints mask conversion results to stdout."
  [conversions]
  (println)
  (doseq [{:keys [input prefix mask wildcard]} conversions]
    (println (format "  %-18s  /%-4d  mask %-18s  wildcard %s"
                     input prefix mask wildcard)))
  (println))

(defn print-supernet-result
  "Prints the smallest covering CIDR for input-cidrs to stdout."
  [input-cidrs result-cidr]
  (println (format "\nSupernet of %d network(s):\n" (count input-cidrs)))
  (doseq [c input-cidrs]
    (println (str "  " c)))
  (println (str "\n  => " result-cidr "\n")))

(defn print-validate-result
  "Prints per-input validation results to stdout."
  [results]
  (let [fmt "  %-25s %-6s %-6s %s"]
    (println)
    (println (format fmt "Input" "Status" "Type" "Error"))
    (println (apply str (repeat 70 "-")))
    (doseq [{:keys [input valid type error]} results]
      (println (format fmt input (if valid "ok" "FAIL") (or type "-") (or error ""))))
    (println)))

(defn print-lpm-result
  "Prints longest-prefix-match results to stdout.
  results is a seq of {:ip :match :prefix-str} maps pre-computed in the handler."
  [routes results]
  (let [fmt "  %-20s %-22s %s"]
    (println (format "\nRouting table: %d route(s)\n" (count routes)))
    (println (format fmt "IP" "Best Match" "Prefix"))
    (println (apply str (repeat 55 "-")))
    (doseq [{:keys [ip match prefix-str]} results]
      (println (format fmt
                       ip
                       (or match "(no match)")
                       (or prefix-str "-"))))
    (println)))
