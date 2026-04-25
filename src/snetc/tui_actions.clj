(ns snetc.tui-actions
  "Small internal helpers for TUI command parsing and aliases."
  (:require [clojure.string :as str]
            [snetc.subnet :as subnet]))

(def command-help
  "Commands: split|s /N, hosts|h N, join|j /N, filter|f Q, clear|x, search Q, label TEXT, import PATH, export [edn|json|yaml] [PATH], print, print-selected, help")

(def aliases
  {"s" "split"
   "h" "hosts"
   "j" "join"
   "f" "filter"
   "x" "clear"
   "?" "help"})

(defn normalize-command [cmd]
  (let [cmd (str/lower-case (or cmd ""))]
    (get aliases cmd cmd)))

(defn parse-command [input]
  (let [[cmd & args] (str/split (str/trim (or input "")) #"\s+")]
    {:cmd (normalize-command cmd)
     :args args
     :arg (str/join " " args)}))

(defn parse-positive-long [s]
  (try
    (let [n (Long/parseLong (str/trim (str s)))]
      (when (pos? n) n))
    (catch Exception _ nil)))

(defn parse-prefix-input [s]
  (let [raw (str/trim (str s))
        raw (if (str/starts-with? raw "/") (subs raw 1) raw)]
    (try
      (let [n (Integer/parseInt raw)]
        (when (subnet/valid-prefix? n) n))
      (catch Exception _ nil))))
