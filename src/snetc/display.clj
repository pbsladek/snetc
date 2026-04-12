(ns snetc.display
  "Terminal display: all human-readable output for snetc commands."
  (:require [clojure.string   :as str]
            [snetc.ip         :as ip]
            [snetc.subnet     :as subnet]
            [snetc.classify   :as classify]
            [snetc.ops        :as ops]))

(defn- label-row [label value]
  (format "  %-18s %s" (str label ":") value))

(defn- ip-role
  "Returns :network, :broadcast, or :host for ip relative to info."
  [ip info]
  (cond
    (= ip (:network   info)) :network
    (= ip (:broadcast info)) :broadcast
    :else                    :host))

(defn print-subnet-info
  "Prints a formatted summary of info to stdout."
  [info]
  (println (str "\n" (:cidr info)))
  (println (label-row "Network"       (:network    info)))
  (println (label-row "Broadcast"     (:broadcast  info)))
  (println (label-row "First Host"    (:first-host info)))
  (println (label-row "Last Host"     (:last-host  info)))
  (println (label-row "Hosts"         (:hosts      info)))
  (println (label-row "Subnet Mask"   (:mask       info)))
  (println (label-row "Wildcard Mask" (:wildcard   info)))
  (println))

(defn print-split-table
  "Prints a table of subnets to stdout."
  [subnets]
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
                       (:broadcast  s)
                       (:hosts      s))))
    (println)))

(defn- tree-lines
  "Returns formatted lines for node, indented with ASCII box-drawing characters."
  [node prefix-str last?]
  (let [info     (:info node)
        children (:children node)
        branch   (if last? "└─ " "├─ ")
        summary  (str prefix-str branch (:cidr info) "  [" (:hosts info) " hosts]")
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
    (println (str "\n" (:cidr info) "  [" (:hosts info) " hosts]"))
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
  "Prints a containment table for ips against cidr to stdout."
  [cidr ips]
  (let [info (subnet/subnet-info cidr)
        fmt  "  %-20s %-6s %s"]
    (println (str "\nSubnet: " cidr "\n"))
    (println (format fmt "IP" "Match" "Note"))
    (println (apply str (repeat 50 "-")))
    (doseq [ip ips]
      (let [in?  (subnet/ip-in-cidr? ip cidr)
            role (when in? (ip-role ip info))
            note (case role
                   :network   "(network address)"
                   :broadcast "(broadcast address)"
                   nil)]
        (println (format fmt ip (if in? "yes" "no") (or note "")))))
    (println)))

(defn print-free-result
  "Prints free space within parent-cidr after removing allocated-cidrs to stdout."
  [parent-cidr allocated-cidrs result-cidrs]
  (println (str "\nFree space in " parent-cidr
                " (excluding " (count allocated-cidrs) " allocated block(s)):\n"))
  (if (empty? result-cidrs)
    (println "  (none – fully allocated)\n")
    (let [fmt "  %-20s %-18s %s"]
      (println (format fmt "CIDR" "Subnet Mask" "Hosts"))
      (println (apply str (repeat 50 "-")))
      (doseq [c result-cidrs]
        (let [info (subnet/subnet-info c)]
          (println (format fmt c (:mask info) (:hosts info)))))
      (println))))

(defn print-diff-result
  "Prints a sorted diff of before-cidrs vs after-cidrs to stdout."
  [before-cidrs after-cidrs {:keys [added removed unchanged]}]
  (println (format "\nDiff: %d → %d network(s)\n" (count before-cidrs) (count after-cidrs)))
  (let [all (->> (concat (map #(vector :removed   %) removed)
                         (map #(vector :unchanged %) unchanged)
                         (map #(vector :added     %) added))
                 (sort-by (fn [[_ c]] (first (subnet/cidr->range c)))))]
    (doseq [[status cidr] all]
      (println (format "  %s %s"
                       (case status :added "[+]" :removed "[-]" :unchanged "[=]")
                       cidr))))
  (println (format "\n  Added: %d  Removed: %d  Unchanged: %d\n"
                   (count added) (count removed) (count unchanged))))

(defn print-classify-result
  "Prints RFC classification for each input to stdout."
  [inputs]
  (let [fmt "  %-22s %-32s %-12s %s"]
    (println)
    (println (format fmt "Input" "Category" "RFC" "Routable"))
    (println (apply str (repeat 80 "-")))
    (doseq [input inputs]
      (let [{:keys [name rfc routable? spans? bcast-name]} (classify/classify input)
            rfc-str (if (str/blank? rfc) "-" rfc)
            note    (when spans? (format " → %s" bcast-name))]
        (println (format fmt input (str name (or note "")) rfc-str (if routable? "yes" "no")))))
    (println)))

(defn print-range-result
  "Prints the minimal CIDR list for the range start-ip to end-ip to stdout."
  [start-ip end-ip cidrs]
  (let [total (inc (- (ip/ip->long end-ip) (ip/ip->long start-ip)))]
    (println (format "\nRange: %s – %s  (%d address%s)\n"
                     start-ip end-ip total (if (= 1 total) "" "es")))
    (doseq [c cidrs] (println (str "  " c)))
    (println (format "\n  %d CIDR block(s)\n" (count cidrs)))))

(defn print-vlsm-result
  "Prints the VLSM allocation table for parent-cidr to stdout."
  [parent-cidr allocations]
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
    (println)))

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

(defn print-lpm-result
  "Prints longest-prefix-match results for ips against routes to stdout."
  [routes ips]
  (let [fmt "  %-20s %-22s %s"]
    (println (format "\nRouting table: %d route(s)\n" (count routes)))
    (println (format fmt "IP" "Best Match" "Prefix"))
    (println (apply str (repeat 55 "-")))
    (doseq [ip ips]
      (let [match (ops/longest-prefix-match ip routes)]
        (println (format fmt
                         ip
                         (or match "(no match)")
                         (if match (str "/" (:prefix (subnet/parse-cidr match))) "-")))))
    (println)))
