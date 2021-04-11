(ns snetc
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as string]
            [clojure.pprint :refer [print-table]])
  (:gen-class))

(defn parse-int-nth [match, index]
  (Integer/parseInt (nth match index)))

(defn inet-ntoa
  "Converts the Internet host address in, given in network byte order, to a string in IPv4 dotted-decimal notation"
  [in]
  (let [first (bit-and (clojure.lang.Numbers/shiftRightInt in 24) 0xff)
        second (bit-and (clojure.lang.Numbers/shiftRightInt in 16) 0xff)
        third (bit-and (clojure.lang.Numbers/shiftRightInt in 8) 0xff)
        fourth (bit-and in 0xff)]
    (str first "." second "." third "." fourth)))

(defn inet-aton
  "Converts the Internet host address ha from the IPv4 numbers-and-dots notation into binary form (in network byte order)"
  [ha]
  (let [cidr-format #"^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$"
        match (string/split (first (re-matches cidr-format ha)) #"\.")
        first (clojure.lang.Numbers/shiftLeftInt (parse-int-nth match 0) 24)
        second (clojure.lang.Numbers/shiftLeftInt (parse-int-nth match 1) 16)
        third (clojure.lang.Numbers/shiftLeftInt (parse-int-nth match 2) 8)
        fourth (parse-int-nth match 3)]
    (bit-or (bit-or (bit-or first second) third) fourth)))

(defn network-address
  [ip, mask]
  (loop [i (- 31 mask)
         ipa  ip]
    (if (zero? i)
      ipa
      (recur (dec i)
             (bit-and ipa (clojure.lang.Numbers/shiftLeftInt (bit-not 1) i))))))

(defn subnet-addresses [mask]
  (let [right (- 32 mask)]
    (clojure.lang.Numbers/shiftLeftInt 1 right)))

(defn subnet-last-address [subnet, mask]
  (- (+ subnet (subnet-addresses mask)) 1))

(defn subnet-netmask [mask]
  (network-address -1 mask))

(defn hosts [useable-first useable-last mask]
  (cond
    (= mask 32) "1"
    (>= mask 31) "2"
    :else ((+ (- useable-last useable-first) 1))))

(defn useable-range [network, mask]
  (let [aton (inet-aton network)
        net (network-address aton mask)
        net-last-address (subnet-last-address aton mask)
        useable-first (+ net 1)
        useable-last (- net-last-address 1)
        useable-range (str (inet-ntoa useable-first) " - " (inet-ntoa useable-last))]
    useable-range))

(defn full-range [network, mask]
  (let [aton (inet-aton network)
        net (network-address aton mask)
        net-last-address (subnet-last-address aton mask)]
    (str (inet-ntoa net) " - " (inet-ntoa net-last-address))))

(defn gen-table-data [net, mask, useable-range, full-range]
  [{:network (inet-ntoa net)
    :mask mask
    :useable-range useable-range
    :full-range full-range
    :hosts (hosts useable-first useable-last mask)}]))

(defn gen [options]
  (let [network (get options :network)
        mask (get options :mask)
        aton (inet-aton network)
        net (network-address aton mask)
        useable-range (useable-range network mask)
        full-range (full-range network mask)]
    (print-table (gen-table-data net mask useable-range full-range))))

(def cli-options
  [["-n" "--network 192.168.0.0" "Network address"
    :validate [#(re-matches #"^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$" %) "Must be a valid cidr"]]
   ["-m" "--mask 22" "Mask"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 33) "The network mask you have entered is invalid. Must be 0 < 33."]]
  ;;  ["-v" nil "Verbosity level"
  ;;   :id :verbosity
  ;;   :default 0
  ;;   :update-fn inc]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Subnet calculator"
        ""
        "Usage: snetc [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  gen    Generate subnets"
        ""
        "Please refer to the manual page for more information."]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}
      errors
      {:exit-message (error-msg errors)}
      (and (= 1 (count arguments))
           (#{"gen"} (first arguments)))
      {:action (first arguments) :options options}
      :else
      {:exit-message (usage summary)})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        "gen"  (gen options)))))