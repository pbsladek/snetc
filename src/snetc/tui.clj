(ns snetc.tui
  "Dependency-free terminal UI for interactive subnet planning."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [snetc.plan :as plan]
            [snetc.subnet :as subnet]
            [snetc.tui-actions :as actions]
            [snetc.tui-model :as model]
            [snetc.tui-render :as render])
  (:import [java.io BufferedReader FileInputStream InputStream InputStreamReader]
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
  (let [proc  (start-shell cmd)
        out   (slurp (.getInputStream ^Process proc))
        err   (slurp (.getErrorStream ^Process proc))
        exit  (.waitFor ^Process proc)]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed: " cmd "\n" (str/trim err))
                      {:cmd cmd :exit exit :err err})))
    (str/trim out)))

(defn- env-terminal-size []
  [(parse-int-or (System/getenv "COLUMNS") default-width)
   (parse-int-or (System/getenv "LINES") default-height)])

(def ^:private cached-size (atom [default-width default-height]))
(def ^:private size-queried-at (atom 0))

(defn- terminal-size []
  (let [now (System/currentTimeMillis)]
    (when (> (- now @size-queried-at) 2000)
      (let [size (try
                   (let [[rows cols] (map #(parse-int-or % 0)
                                         (str/split (sh-output "stty size < /dev/tty") #"\s+"))]
                     (if (and (pos? rows) (pos? cols))
                       [cols rows]
                       (env-terminal-size)))
                   (catch Exception _
                     (env-terminal-size)))]
        (reset! cached-size size)
        (reset! size-queried-at now)))
    @cached-size))

(defn- sh! [cmd]
  (let [proc  (start-shell cmd)
        err   (slurp (.getErrorStream ^Process proc))
        exit  (.waitFor ^Process proc)]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed: " cmd "\n" (str/trim err))
                      {:cmd cmd :exit exit :err err})))))

(defn- terminal-mode []
  (sh-output "stty -g < /dev/tty"))

(defn- set-terminal-mode! [mode]
  (sh! (str "stty " mode " < /dev/tty")))

(defn- raw-mode! []
  (sh! "stty raw -echo min 0 time 1 < /dev/tty"))

(defn- enter-screen! []
  (print "\u001b[?1049h\u001b[?25l\u001b[?7l")
  (flush))

(defn- leave-screen! []
  (print "\u001b[0m\u001b[?7h\u001b[?25h\u001b[?1049l")
  (flush))

(defn- read-escape [^InputStream in]
  (let [b1 (.read in)]
    (if (= -1 b1)
      :escape
      (let [b2 (.read in)]
        (if (= -1 b2)
          :escape
          (case [b1 b2]
            [91 65] :up   [91 66] :down
            [91 67] :right [91 68] :left
            [79 65] :up   [79 66] :down
            [91 53] (do (.read in) :page-up)
            [91 54] (do (.read in) :page-down)
            (do
              (when (= b1 91)
                (loop []
                  (let [b (.read in)]
                    (when (and (not= -1 b) (< b 64))
                      (recur)))))
              :escape)))))))

(defn- read-key [^InputStream in]
  (let [b (.read in)]
    (case b
      -1 :timeout
      3 :quit
      8 :join
      9 :down
      10 :split
      13 :split
      27 (read-escape in)
      32 :split
      47 :jump
      58 :command
      70 :filter
      71 :last
      72 :split-hosts
      74 :join
      80 :print-cidrs
      81 :quit
      82 :redo
      83 :split-to-prefix
      85 :undo
      101 :export
      102 :filter
      103 :first
      105 :import
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

(defn- open-tty-input []
  (FileInputStream. "/dev/tty"))

(declare terminal)

(defn- refresh-derived [state]
  (let [rows (or (:rows state) (model/rows (:plan state)))
        filtered (model/filter-rows rows (:filter state))
        selected (render/clamp-selected (:selected state) (count filtered))]
    (assoc state
           :rows rows
           :filtered-rows filtered
           :selected selected
           :summary {:visible-count (count filtered)
                     :prefixes (model/prefix-histogram filtered)})))

(defn- refresh-rows [state]
  (refresh-derived (assoc state :rows (model/rows (:plan state)))))

(defn- state-rows [state]
  (or (:filtered-rows state)
      (model/filter-rows (or (:rows state) (model/rows (:plan state)))
                         (:filter state))))

(defn- render-width [terminal-width]
  (let [screen-width (max 1 terminal-width)]
    (if (> screen-width 1) (dec screen-width) screen-width)))

(defn- cached-layout [state width rows]
  (let [key [(render-width width) (System/identityHashCode rows)]
        cache (:layout-cache state)]
    (if (= key (:key cache))
      [state (:layout cache)]
      (let [layout (render/layout (first key) rows)]
        [(assoc state :layout-cache {:key key :layout layout}) layout]))))

(defn- selected-row [{:keys [selected] :as state}]
  (nth (state-rows state) selected nil))

(defn- select-index [state idx]
  (let [state (cond-> state (nil? (:filtered-rows state)) refresh-derived)
        rows (state-rows state)
        idx (render/clamp-selected idx (count rows))
        cidr (:cidr (nth rows idx nil))]
    (cond-> (assoc state :selected idx)
      cidr (assoc-in [:plan :cursor] cidr))))

(defn- select-cursor [state]
  (let [state (cond-> state (nil? (:filtered-rows state)) refresh-derived)
        rows (state-rows state)
        cursor (:cursor (:plan state))
        idx (index-of (mapv :cidr rows) cursor)]
    (select-index state (if (neg? idx) 0 idx))))

(defn- move-selection [state delta]
  (select-index state (+ (:selected state) delta)))

(defn- move-page [state direction]
  (let [frame (:last-frame state)
        body-count (max 1 (- (count (:lines frame)) 8))]
    (move-selection state (* direction body-count))))

(defn- clear-filter [state]
  (if (:filter state)
    (-> state
        (assoc :filter nil :message "Filter cleared")
        refresh-derived
        select-cursor)
    state))

(defn- apply-plan-change [state f message-f]
  (if-let [cidr (:cidr (selected-row state))]
    (try
      (let [new-plan (f (:plan state) cidr)]
        (-> state
            (assoc :plan new-plan
                   :message (message-f cidr new-plan))
            refresh-rows
            select-cursor))
      (catch Exception e
        (assoc state :message (ex-message e))))
    (assoc state :message "No subnet selected")))

(defn- apply-plan-op [state f success-message]
  (apply-plan-change state
                     (fn [planner cidr] (f planner cidr))
                     (fn [cidr _] (success-message cidr))))

(def ^:private bulk-split-confirm-threshold 1024)

(defn- split-leaf-count [cidr target-prefix]
  (let [prefix (:prefix (subnet/parse-cidr cidr))]
    (when (>= target-prefix prefix)
      (bit-shift-left 1 (- target-prefix prefix)))))

(defn- confirm-bulk-split? [state saved-mode cidr target-prefix]
  (let [leaf-count (or (split-leaf-count cidr target-prefix) 0)]
    (if (<= leaf-count bulk-split-confirm-threshold)
      true
      (let [term (terminal state)
            answer ((:prompt term) saved-mode
                    (str "Split would create " leaf-count " leaves. Type yes to continue: "))]
        (= "yes" (str/lower-case (str/trim (or answer ""))))))))

(defn- split-selected-to-prefix [state saved-mode target-prefix message-f]
  (if-let [cidr (:cidr (selected-row state))]
    (if (confirm-bulk-split? state saved-mode cidr target-prefix)
      (apply-plan-change state
                         #(plan/split-leaf-to-prefix %1 %2 target-prefix)
                         message-f)
      (assoc state :message "Split cancelled"))
    (assoc state :message "No subnet selected")))

(defn- prompt-line [saved-mode prompt]
  (set-terminal-mode! saved-mode)
  (print (str "\u001b[?25h\r\n" prompt))
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
    (let [term (terminal state)
          label ((:prompt term) saved-mode (str "Label for " cidr " (blank clears): "))]
      (try
        (let [new-plan (plan/label-leaf (:plan state) cidr label)]
          (-> state
              (assoc :plan new-plan
                     :rows (if (:rows state)
                             (model/replace-row (:rows state) new-plan cidr)
                             (model/rows new-plan))
                     :message (if (str/blank? label)
                                (str "Cleared label for " cidr)
                                (str "Labeled " cidr)))
              refresh-derived
              select-cursor))
        (catch Exception e
          (assoc state :message (ex-message e)))))
    (assoc state :message "No subnet selected")))

(defn- jump-to-cidr [state saved-mode]
  (let [term (terminal state)
        input ((:prompt term) saved-mode "Search/jump: ")]
    (try
      (let [rows (state-rows state)
            idx (or (first (keep-indexed (fn [idx row]
                                            (when (model/row-matches-query? row input) idx))
                                          rows))
                    -1)]
        (if (neg? idx)
          (assoc state :message (str "No match for " input))
          (-> state
              (select-index idx)
              (assoc :message (str "Selected " (:cidr (nth rows idx)))))))
      (catch Exception e
        (assoc state :message (ex-message e))))))

(defn- apply-filter [state query]
  (let [query (str/trim (or query ""))
        next-state (assoc state :filter (when-not (str/blank? query) query))
        next-state (refresh-derived next-state)
        rows (state-rows next-state)]
    (if (empty? rows)
      (assoc next-state :selected 0 :message (str "Filter matched no rows: " query))
      (-> next-state
          (select-index 0)
          (assoc :message (if (str/blank? query)
                            "Filter cleared"
                            (str "Filter: " query " (" (count rows) " row(s))")))))))

(defn- filter-with-prompt [state saved-mode]
  (let [term (terminal state)
        query ((:prompt term) saved-mode "Filter (blank clears): ")]
    (apply-filter state query)))

(defn- split-to-prefix-with-prompt [state saved-mode]
  (let [term (terminal state)
        prefix (actions/parse-prefix-input ((:prompt term) saved-mode "Split selected to prefix: "))]
    (if prefix
      (split-selected-to-prefix state saved-mode prefix
                                (fn [cidr _] (str "Split " cidr " to /" prefix)))
      (assoc state :message "Invalid prefix"))))

(defn- split-for-hosts-with-prompt [state saved-mode]
  (let [term (terminal state)
        hosts (actions/parse-positive-long ((:prompt term) saved-mode "Split selected for hosts: "))]
    (if hosts
      (let [prefix (plan/hosts->target-prefix hosts)]
        (split-selected-to-prefix state saved-mode prefix
                                  (fn [cidr _] (str "Split " cidr " for " hosts " host(s)"))))
      (assoc state :message "Invalid host count"))))

(defn- join-to-prefix-with-prompt [state saved-mode]
  (let [term (terminal state)
        prefix (actions/parse-prefix-input ((:prompt term) saved-mode "Join selected up to prefix: "))]
    (if prefix
      (apply-plan-change state
                         #(plan/join-leaf-to-prefix %1 %2 prefix)
                         (fn [cidr _] (str "Joined " cidr " toward /" prefix)))
      (assoc state :message "Invalid prefix"))))

(defn- write-edn-plan! [planner]
  (let [file (io/file "snetc-plan.edn")]
    (spit file (with-out-str (prn (plan/export-plan planner))))
    (.getPath file)))

(defn- write-edn-plan-to! [planner path]
  (let [file (io/file path)]
    (spit file (with-out-str (prn (plan/export-plan planner))))
    (.getPath file)))

(defn- write-leaf-cidrs! [planner]
  (let [file (io/file "snetc-leaves.txt")]
    (spit file (str (str/join "\n" (plan/leaf-cidrs planner)) "\n"))
    (.getPath file)))

(defn- write-selected-cidr! [row]
  (let [file (io/file "snetc-selected.txt")]
    (spit file (str (:cidr row) "\n"))
    (.getPath file)))

(defn- write-json-plan! [planner]
  (let [file (io/file "snetc-plan.json")]
    (spit file (str (json/write-str (plan/export-plan planner) :key-fn name) "\n"))
    (.getPath file)))

(defn- write-json-plan-to! [planner path]
  (let [file (io/file path)]
    (spit file (str (json/write-str (plan/export-plan planner) :key-fn name) "\n"))
    (.getPath file)))

(defn- node->yaml-lines [node indent]
  (let [pad (apply str (repeat indent " "))
        label-val (if (:label node)
                    (str "\""
                         (-> (:label node)
                             (str/replace "\\" "\\\\")
                             (str/replace "\"" "\\\"")
                             (str/replace "\n" "\\n")
                             (str/replace "\r" "\\r")
                             (str/replace "\t" "\\t"))
                         "\"")
                    "null")]
    (into [(str pad "cidr: " (:cidr node))
           (str pad "label: " label-val)]
          (if (seq (:children node))
            (into [(str pad "children:")]
                  (mapcat (fn [child]
                            (let [lines (node->yaml-lines child (+ indent 2))]
                              (cons (str pad "- " (subs (first lines) (+ indent 2)))
                                    (rest lines))))
                          (:children node)))
            [(str pad "children: null")]))))

(defn- plan->yaml [data]
  (str/join "\n"
            (into [(str "version: " (:version data))
                   (str "parent: " (:parent data))
                   "root:"]
                  (node->yaml-lines (:root data) 2))))

(defn- write-yaml-plan! [planner]
  (let [file (io/file "snetc-plan.yaml")]
    (spit file (str (plan->yaml (plan/export-plan planner)) "\n"))
    (.getPath file)))

(defn- write-yaml-plan-to! [planner path]
  (let [file (io/file path)]
    (spit file (str (plan->yaml (plan/export-plan planner)) "\n"))
    (.getPath file)))

(defn- read-plan-file [path]
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info (str "Import file not found: " path) {:path path})))
    (let [text (slurp file)
          json? (str/ends-with? (str/lower-case path) ".json")
          data (try
                 (if json?
                   (json/read-str text :key-fn keyword)
                   (edn/read-string text))
                 (catch Exception e
                   (throw (ex-info (str "Could not parse " (if json? "JSON" "EDN")
                                        " plan: " (ex-message e))
                                   {:path path}
                                   e))))]
      (try
        (plan/import-plan data)
        (catch Exception e
          (throw (ex-info (str "Invalid plan: " (ex-message e))
                          {:path path}
                          e)))))))

(defn- import-plan-path [state path]
  (let [term (terminal state)]
    (try
      (-> state
          (assoc :plan ((:read-plan term) path)
                 :filter nil
                 :message (str "Imported " path))
          refresh-rows
          select-cursor)
      (catch Exception e
        (assoc state :message (ex-message e))))))

(def ^:private default-terminal
  {:terminal-mode terminal-mode
   :set-terminal-mode! set-terminal-mode!
   :raw-mode! raw-mode!
   :enter-screen! enter-screen!
   :leave-screen! leave-screen!
   :terminal-size terminal-size
   :open-input open-tty-input
   :read-key read-key
   :prompt prompt-line
   :write-edn-plan write-edn-plan!
   :write-edn-plan-to write-edn-plan-to!
   :write-json-plan write-json-plan!
   :write-json-plan-to write-json-plan-to!
   :write-yaml-plan write-yaml-plan!
   :write-yaml-plan-to write-yaml-plan-to!
   :write-leaf-cidrs write-leaf-cidrs!
   :write-selected-cidr write-selected-cidr!
   :read-plan read-plan-file
   :write! print})

(defn- terminal [state]
  (merge default-terminal (:terminal state)))

(defn- initial-state [planner terminal]
  (refresh-rows {:plan planner
                 :selected 0
                 :scroll 0
                 :message "Ready"
                 :terminal terminal}))

(defn- export-with-prompt [state saved-mode]
  (let [term (terminal state)
        input (str/trim (or ((:prompt term) saved-mode
                             "Export [e]dn/[j]son/[y]aml [path] (enter=edn): ")
                            ""))
        [fmt path] (str/split input #"\s+" 2)
        fmt (str/lower-case (or fmt ""))]
    (try
      (let [path (case fmt
                   "json" (if path
                            ((:write-json-plan-to term) (:plan state) path)
                            ((:write-json-plan term) (:plan state)))
                   "yaml" (if path
                            ((:write-yaml-plan-to term) (:plan state) path)
                            ((:write-yaml-plan term) (:plan state)))
                   "edn" (if path
                           ((:write-edn-plan-to term) (:plan state) path)
                           ((:write-edn-plan term) (:plan state)))
                   "" (if path
                        ((:write-edn-plan-to term) (:plan state) path)
                        ((:write-edn-plan term) (:plan state)))
                   (if path
                     ((:write-edn-plan-to term) (:plan state) path)
                     ((:write-edn-plan term) (:plan state))))]
        (assoc state :message (str "Exported to " path)))
      (catch Exception e
        (assoc state :message (ex-message e))))))

(defn- import-with-prompt [state saved-mode]
  (let [term (terminal state)
        path ((:prompt term) saved-mode "Import plan path (.edn/.json): ")]
    (import-plan-path state path)))

(defn- export-format [state fmt path]
  (let [term (terminal state)]
    (try
      (let [path (case (str/lower-case (str fmt))
                   "json" (if path
                            ((:write-json-plan-to term) (:plan state) path)
                            ((:write-json-plan term) (:plan state)))
                   "yaml" (if path
                            ((:write-yaml-plan-to term) (:plan state) path)
                            ((:write-yaml-plan term) (:plan state)))
                   "edn" (if path
                           ((:write-edn-plan-to term) (:plan state) path)
                           ((:write-edn-plan term) (:plan state)))
                   (if path
                     ((:write-edn-plan-to term) (:plan state) path)
                     ((:write-edn-plan term) (:plan state))))]
        (assoc state :message (str "Exported to " path)))
      (catch Exception e
        (assoc state :message (ex-message e))))))

(defn- command-palette [state saved-mode]
  (let [term (terminal state)
        input (str/trim (or ((:prompt term) saved-mode "Command: ") ""))
        {:keys [cmd args arg]} (actions/parse-command input)]
    (case cmd
      "" state
      "help" (assoc state :message actions/command-help)
      "clear" (apply-filter state "")
      "filter" (apply-filter state arg)
      "search" (let [idx (or (first (keep-indexed (fn [idx row]
                                                    (when (model/row-matches-query? row arg) idx))
                                                  (state-rows state)))
                              -1)]
                 (if (neg? idx)
                   (assoc state :message (str "No match for " arg))
                   (-> state (select-index idx) (assoc :message (str "Selected " arg)))))
      "split" (if-let [prefix (actions/parse-prefix-input arg)]
                (split-selected-to-prefix state saved-mode prefix
                                          (fn [cidr _] (str "Split " cidr " to /" prefix)))
                (assoc state :message "Usage: split /N"))
      "split-to" (if-let [prefix (actions/parse-prefix-input arg)]
                   (split-selected-to-prefix state saved-mode prefix
                                             (fn [cidr _] (str "Split " cidr " to /" prefix)))
                   (assoc state :message "Usage: split-to /N"))
      "hosts" (if-let [hosts (actions/parse-positive-long arg)]
                (let [prefix (plan/hosts->target-prefix hosts)]
                  (split-selected-to-prefix state saved-mode prefix
                                            (fn [cidr _] (str "Split " cidr " for " hosts " host(s)"))))
                (assoc state :message "Usage: hosts N"))
      "join" (if-let [prefix (actions/parse-prefix-input arg)]
               (apply-plan-change state
                                  #(plan/join-leaf-to-prefix %1 %2 prefix)
                                  (fn [cidr _] (str "Joined " cidr " toward /" prefix)))
               (assoc state :message "Usage: join /N"))
      "label" (if-let [cidr (:cidr (selected-row state))]
                (try
                  (let [new-plan (plan/label-leaf (:plan state) cidr arg)]
                    (-> state
                        (assoc :plan new-plan
                               :rows (if (:rows state)
                                       (model/replace-row (:rows state) new-plan cidr)
                                       (model/rows new-plan))
                               :message (if (str/blank? arg)
                                          (str "Cleared label for " cidr)
                                          (str "Labeled " cidr)))
                        refresh-derived
                        select-cursor))
                  (catch Exception e
                    (assoc state :message (ex-message e))))
                (assoc state :message "No subnet selected"))
      "import" (import-plan-path state arg)
      "export" (let [[fmt path] args]
                 (export-format state (if (str/blank? (str fmt)) "edn" fmt) path))
      "print" (try
                (assoc state :message
                       (str "Wrote leaf CIDRs to "
                            ((:write-leaf-cidrs term) (:plan state))))
                (catch Exception e
                  (assoc state :message (ex-message e))))
      "print-selected" (if-let [row (selected-row state)]
                         (try
                           (assoc state :message
                                  (str "Wrote selected CIDR to "
                                       ((:write-selected-cidr term) row)))
                           (catch Exception e
                             (assoc state :message (ex-message e))))
                         (assoc state :message "No subnet selected"))
      (assoc state :message (str "Unknown command: " cmd)))))

(defn- handle-key [state key saved-mode]
  (case key
    :up (move-selection state -1)
    :left (move-selection state -1)
    :down (move-selection state 1)
    :right (move-selection state 1)
    :first (select-index state 0)
    :last (select-index state (dec (count (state-rows state))))
    :page-up (move-page state -1)
    :page-down (move-page state 1)
    :escape (clear-filter state)
    :split (apply-plan-op state plan/split-leaf #(str "Split " %))
    :split-to-prefix (split-to-prefix-with-prompt state saved-mode)
    :split-hosts (split-for-hosts-with-prompt state saved-mode)
    :join (apply-plan-op state plan/join-leaf
                         #(str "Joined " % " into " (plan/parent-cidr %)))
    :join-to-prefix (join-to-prefix-with-prompt state saved-mode)
    :undo (-> state
              (update :plan plan/undo)
              refresh-rows
              select-cursor
              (assoc :message "Undo"))
    :redo (-> state
              (update :plan plan/redo)
              refresh-rows
              select-cursor
              (assoc :message "Redo"))
    :label (label-selected state saved-mode)
    :jump (jump-to-cidr state saved-mode)
    :filter (filter-with-prompt state saved-mode)
    :command (command-palette state saved-mode)
    :import (import-with-prompt state saved-mode)
    :export (export-with-prompt state saved-mode)
    :print-cidrs (try
                   (let [term (terminal state)]
                     (assoc state :message
                            (str "Wrote leaf CIDRs to "
                                 ((:write-leaf-cidrs term) (:plan state)))))
                   (catch Exception e
                     (assoc state :message (ex-message e))))
    :unknown (assoc state :message "Unknown key")
    state))

(defn- render-state [state width height]
  (let [state (cond-> state (nil? (:filtered-rows state)) refresh-derived)
        rows (state-rows state)
        selected (render/clamp-selected (:selected state) (count rows))
        [state layout] (cached-layout state width rows)
        summary (assoc (:summary state)
                       :selected (nth rows selected nil))
        frame (render/frame (assoc state
                                   :rows rows
                                   :selected selected
                                   :summary summary
                                   :layout layout)
                            width height)]
    [(assoc state
            :selected (:selected frame)
            :scroll (:scroll frame)
            :last-frame frame)
     frame]))

(defn run-tree!
  "Runs the interactive subnet planner for parent-cidr.
  Returns the final plan when the user quits."
  ([parent-cidr]
   (run-tree! parent-cidr default-terminal))
  ([parent-cidr terminal-overrides]
   (when (= "dumb" (System/getenv "TERM"))
     (throw (ex-info "snetc tree requires a colour terminal (TERM=dumb detected)" {})))
   (let [term (merge default-terminal terminal-overrides)
         planner (plan/new-plan parent-cidr)
         saved-mode ((:terminal-mode term))]
     (with-open [^InputStream tty-in ((:open-input term))]
       (try
         ((:enter-screen! term))
         ((:raw-mode! term))
         (loop [state (initial-state planner term)]
           (let [[width height] ((:terminal-size term))
                 old-frame (:last-frame state)
                 [state frame] (render-state state width height)
                 output (render/diff-screen old-frame frame)]
             ((:write! term) output)
             (flush)
             (let [key ((:read-key term) tty-in)]
               (if (#{:quit} key)
                 (:plan state)
                 (recur (handle-key state key saved-mode))))))
         (finally
           ((:set-terminal-mode! term) saved-mode)
           ((:leave-screen! term))))))))
