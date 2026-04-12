(ns snetc.tui
  "Dependency-free terminal UI for interactive subnet planning."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [snetc.plan :as plan]
            [snetc.subnet :as subnet]
            [snetc.tui-render :as render])
  (:import [java.io BufferedReader FileInputStream InputStreamReader]
           [java.lang ProcessBuilder]
           [java.util ArrayList List]))

(def default-width 120)
(def default-height 30)

(defn- parse-int-or [s fallback]
  (try
    (Integer/parseInt s)
    (catch Exception _ fallback)))

(defn- index-of [xs needle]
  (or (first (keep-indexed (fn [idx x] (when (= needle x) idx)) xs))
      -1))

(defn- start-shell [cmd]
  (let [args (doto (ArrayList.)
               (.add "sh")
               (.add "-c")
               (.add cmd))
        pb (ProcessBuilder. ^List args)]
    (.start ^ProcessBuilder pb)))

(defn- sh-output [cmd]
  (let [proc (start-shell cmd)
        exit (.waitFor ^Process proc)
        out (slurp (.getInputStream ^Process proc))
        err (slurp (.getErrorStream ^Process proc))]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed: " cmd "\n" (str/trim err))
                      {:cmd cmd :exit exit :err err})))
    (str/trim out)))

(defn- env-terminal-size []
  [(parse-int-or (System/getenv "COLUMNS") default-width)
   (parse-int-or (System/getenv "LINES") default-height)])

(defn- terminal-size []
  (try
    (let [[rows cols] (map #(parse-int-or % 0)
                           (str/split (sh-output "stty size < /dev/tty") #"\s+"))]
      (if (and (pos? rows) (pos? cols))
        [cols rows]
        (env-terminal-size)))
    (catch Exception _
      (env-terminal-size))))

(defn- sh! [cmd]
  (let [proc (start-shell cmd)
        exit (.waitFor ^Process proc)
        err (slurp (.getErrorStream ^Process proc))]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed: " cmd "\n" (str/trim err))
                      {:cmd cmd :exit exit :err err})))))

(defn- terminal-mode []
  (sh-output "stty -g < /dev/tty"))

(defn- set-terminal-mode! [mode]
  (sh! (str "stty " mode " < /dev/tty")))

(defn- raw-mode! []
  (sh! "stty raw -echo min 1 time 0 < /dev/tty"))

(defn- enter-screen! []
  (print "\u001b[?1049h\u001b[?25l\u001b[?7l")
  (flush))

(defn- leave-screen! []
  (print "\u001b[0m\u001b[?7h\u001b[?25h\u001b[?1049l")
  (flush))

(defn- read-escape [^FileInputStream in]
  (let [b1 (.read in)
        b2 (.read in)]
    (case [b1 b2]
      [91 65] :up
      [91 66] :down
      [91 67] :right
      [91 68] :left
      [79 65] :up
      [79 66] :down
      :escape)))

(defn- read-key [^FileInputStream in]
  (let [b (.read in)]
    (case b
      -1 :eof
      3 :quit
      8 :join
      9 :down
      10 :split
      13 :split
      27 (read-escape in)
      32 :split
      47 :jump
      74 :join
      80 :print-cidrs
      81 :quit
      82 :redo
      83 :split
      85 :undo
      101 :export
      106 :down
      107 :up
      108 :label
      112 :print-cidrs
      113 :quit
      114 :redo
      115 :split
      117 :undo
      127 :join
      :unknown)))

(defn- selected-row [{:keys [plan selected]}]
  (nth (render/rows plan) selected nil))

(defn- select-index [state idx]
  (let [rows (render/rows (:plan state))
        idx (render/clamp-selected idx (count rows))
        cidr (:cidr (nth rows idx nil))]
    (cond-> (assoc state :selected idx)
      cidr (assoc-in [:plan :cursor] cidr))))

(defn- select-cursor [state]
  (let [rows (render/rows (:plan state))
        cursor (:cursor (:plan state))
        idx (index-of (mapv :cidr rows) cursor)]
    (select-index state (if (neg? idx) 0 idx))))

(defn- move-selection [state delta]
  (select-index state (+ (:selected state) delta)))

(defn- apply-plan-op [state f success-message]
  (if-let [cidr (:cidr (selected-row state))]
    (try
      (-> state
          (assoc :plan (f (:plan state) cidr)
                 :message (success-message cidr))
          select-cursor)
      (catch Exception e
        (assoc state :message (ex-message e))))
    (assoc state :message "No subnet selected")))

(defn- prompt-line [saved-mode prompt]
  (set-terminal-mode! saved-mode)
  (print "\u001b[?25h\r\n" prompt)
  (flush)
  (try
    (with-open [reader (BufferedReader. (InputStreamReader. (FileInputStream. "/dev/tty")))]
      (.readLine reader))
    (finally
      (raw-mode!)
      (print "\u001b[?25l")
      (flush))))

(defn- label-selected [state saved-mode]
  (if-let [cidr (:cidr (selected-row state))]
    (let [label (prompt-line saved-mode (str "Label for " cidr " (blank clears): "))]
      (try
        (-> state
            (assoc :plan (plan/label-leaf (:plan state) cidr label)
                   :message (if (str/blank? label)
                              (str "Cleared label for " cidr)
                              (str "Labeled " cidr)))
            select-cursor)
        (catch Exception e
          (assoc state :message (ex-message e)))))
    (assoc state :message "No subnet selected")))

(defn- jump-to-cidr [state saved-mode]
  (let [input (prompt-line saved-mode "Jump to CIDR: ")]
    (try
      (let [cidr (:cidr (subnet/subnet-info input))
            rows (render/rows (:plan state))
            idx (index-of (mapv :cidr rows) cidr)]
        (if (neg? idx)
          (assoc state :message (str "No visible leaf for " cidr))
          (-> state
              (select-index idx)
              (assoc :message (str "Selected " cidr)))))
      (catch Exception e
        (assoc state :message (ex-message e))))))

(defn- write-edn-plan! [planner]
  (let [file (io/file "snetc-plan.edn")]
    (spit file (with-out-str (prn (plan/export-plan planner))))
    (.getPath file)))

(defn- write-leaf-cidrs! [planner]
  (let [file (io/file "snetc-leaves.txt")]
    (spit file (str (str/join "\n" (plan/leaf-cidrs planner)) "\n"))
    (.getPath file)))

(defn- handle-key [state key saved-mode]
  (case key
    :up (move-selection state -1)
    :left (move-selection state -1)
    :down (move-selection state 1)
    :right (move-selection state 1)
    :split (apply-plan-op state plan/split-leaf #(str "Split " %))
    :join (apply-plan-op state plan/join-leaf
                         #(str "Joined " % " into " (plan/parent-cidr %)))
    :undo (-> state
              (update :plan plan/undo)
              select-cursor
              (assoc :message "Undo"))
    :redo (-> state
              (update :plan plan/redo)
              select-cursor
              (assoc :message "Redo"))
    :label (label-selected state saved-mode)
    :jump (jump-to-cidr state saved-mode)
    :export (try
              (assoc state :message (str "Exported plan to " (write-edn-plan! (:plan state))))
              (catch Exception e
                (assoc state :message (ex-message e))))
    :print-cidrs (try
                   (assoc state :message (str "Wrote leaf CIDRs to " (write-leaf-cidrs! (:plan state))))
                   (catch Exception e
                     (assoc state :message (ex-message e))))
    :unknown (assoc state :message "Unknown key")
    state))

(defn run-tree!
  "Runs the interactive subnet planner for parent-cidr.
  Returns the final plan when the user quits."
  [parent-cidr]
  (let [planner (plan/new-plan parent-cidr)
        saved-mode (terminal-mode)]
    (with-open [tty-in (FileInputStream. "/dev/tty")]
      (try
        (enter-screen!)
        (raw-mode!)
        (loop [state {:plan planner :selected 0 :scroll 0 :message "Ready"}]
          (let [[width height] (terminal-size)]
            (print (render/render state width height))
            (flush)
            (let [key (read-key tty-in)]
              (if (#{:quit :eof} key)
                (:plan state)
                (recur (handle-key state key saved-mode))))))
        (finally
          (set-terminal-mode! saved-mode)
          (leave-screen!))))))
