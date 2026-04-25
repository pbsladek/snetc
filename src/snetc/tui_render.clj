(ns snetc.tui-render
  "Pure rendering helpers for the interactive subnet planner."
  (:require [clojure.string :as str]
            [snetc.tui-model :as model]))

(def min-body-height 1)
(def max-subnet-width 18)
(def max-range-width 32)

(defn- clip [s width]
  (let [s (str s)]
    (cond
      (<= width 0) ""
      (<= (count s) width) s
      :else (subs s 0 width))))

(defn- clip-value [s width]
  (let [s (str s)]
    (cond
      (<= width 0) ""
      (<= (count s) width) s
      (= width 1) ">"
      :else (str (subs s 0 (dec width)) ">"))))

(defn- left [width s]
  (format (str "%-" width "s") (clip s width)))

(defn- left-value [width s]
  (format (str "%-" width "s") (clip-value s width)))

(defn- right [width n]
  (format (str "%" width "d") n))

(defn rows
  "Returns precomputed visible-row data for plan."
  [planner]
  (model/rows planner))

(defn clamp-selected [selected total]
  (cond
    (zero? total) 0
    (neg? selected) 0
    (>= selected total) (dec total)
    :else selected))

(defn adjust-scroll
  "Returns scroll offset that keeps selected visible."
  [selected scroll visible-count total]
  (let [selected (clamp-selected selected total)
        visible-count (max min-body-height visible-count)
        max-scroll (max 0 (- total visible-count))
        scroll (min max-scroll (max 0 scroll))]
    (cond
      (< selected scroll) selected
      (>= selected (+ scroll visible-count)) (min max-scroll (inc (- selected visible-count)))
      :else scroll)))

(defn viewport
  "Returns {:rows :scroll :selected} for the visible body."
  [all-rows selected scroll visible-count]
  (let [total (count all-rows)
        selected (clamp-selected selected total)
        scroll (adjust-scroll selected scroll visible-count total)]
    {:rows (subvec all-rows scroll (min total (+ scroll visible-count)))
     :scroll scroll
     :selected selected}))

(defn- fit-line [width line]
  (clip line width))

(defn- max-column-width [rows header key max-width]
  (min max-width
       (apply max (count header) (map #(count (str (key %))) rows))))

(defn- column-widths [rows]
  {:subnet (max-column-width rows "Subnet" :cidr max-subnet-width)
   :mask (max-column-width rows "Mask" :mask 15)
   :range (max-column-width rows "Range" :range max-range-width)
   :usable (max-column-width rows "Usable IPs" :usable max-range-width)
   :hosts (max-column-width rows "Hosts" :hosts 10)
   :action (max-column-width rows "Act" :action 3)})

(defn- mode-width [mode {:keys [subnet mask range usable hosts action]}]
  (case mode
    :full (+ 7 subnet 2 mask 2 range 2 usable 2 hosts 2 action)
    :standard (+ 7 subnet 2 mask 2 range 2 hosts 2 action)
    :compact (+ 7 subnet 2 range 2 hosts 1 action)
    :tiny (+ 7 subnet 1 hosts 1 action)))

(defn- table-layout [width rows]
  (let [cols (column-widths rows)
        mode (cond
               (<= (mode-width :full cols) width) :full
               (<= (mode-width :standard cols) width) :standard
               (<= (mode-width :compact cols) width) :compact
               :else :tiny)
        base-width (mode-width mode cols)
        label-width (let [remaining (- width base-width 1)]
                      (if (pos? remaining)
                        (min 24 remaining)
                        0))]
    {:mode (cond
             (= :full mode) :full
             (= :standard mode) :standard
             (= :compact mode) :compact
             :else :tiny)
     :cols cols
     :label-width label-width}))

(defn layout
  "Returns the table layout for width and rows.
  The TUI loop caches this per visible row set so idle repaints do not rescan
  every row just to choose columns."
  [width rows]
  (table-layout width rows))

(defn layout-mode [width rows]
  (:mode (layout width rows)))

(defn hidden-columns [mode]
  (case mode
    :full []
    :standard ["usable"]
    :compact ["mask" "usable"]
    :tiny ["mask" "range" "usable"]
    []))

(defn- append-label [{:keys [label-width]} line label]
  (if (pos? label-width)
    (str line " " (left-value label-width (or label "")))
    line))

(defn- table-header [width {:keys [mode cols] :as layout}]
  (let [line (case mode
               :full
               (str "    #  "
                    (left (:subnet cols) "Subnet") "  "
                    (left (:mask cols) "Mask") "  "
                    (left (:range cols) "Range") "  "
                    (left (:usable cols) "Usable IPs") "  "
                    (left (:hosts cols) "Hosts") "  "
                    (left (:action cols) "Act"))

               :standard
               (str "    #  "
                    (left (:subnet cols) "Subnet") "  "
                    (left (:mask cols) "Mask") "  "
                    (left (:range cols) "Range") "  "
                    (left (:hosts cols) "Hosts") "  "
                    (left (:action cols) "Act"))

               :compact
               (str "    #  "
                    (left (:subnet cols) "Subnet") "  "
                    (left (:range cols) "Range") "  "
                    (left (:hosts cols) "Hosts") " "
                    (left (:action cols) "Act"))

               :tiny
               (str "    #  "
                    (left (:subnet cols) "Subnet") " "
                    (left (:hosts cols) "Hosts") " "
                    (left (:action cols) "Act")))]
    (fit-line width (append-label layout line "Lbl"))))

(defn- row-line [width {:keys [mode cols] :as layout} selected? {:keys [idx cidr label mask range usable hosts action]}]
  (let [marker (if selected? ">" " ")
        line (case mode
               :full
               (str marker " " (right 3 idx) "  "
                    (left-value (:subnet cols) cidr) "  "
                    (left-value (:mask cols) mask) "  "
                    (left-value (:range cols) range) "  "
                    (left-value (:usable cols) usable) "  "
                    (right (:hosts cols) hosts) "  "
                    (left-value (:action cols) action))

               :standard
               (str marker " " (right 3 idx) "  "
                    (left-value (:subnet cols) cidr) "  "
                    (left-value (:mask cols) mask) "  "
                    (left-value (:range cols) range) "  "
                    (right (:hosts cols) hosts) "  "
                    (left-value (:action cols) action))

               :compact
               (str marker " " (right 3 idx) "  "
                    (left-value (:subnet cols) cidr) "  "
                    (left-value (:range cols) range) "  "
                    (right (:hosts cols) hosts) " "
                    (left-value (:action cols) action))

               :tiny
               (str marker " " (right 3 idx) "  "
                    (left-value (:subnet cols) cidr) " "
                    (right (:hosts cols) hosts) " "
                    (left-value (:action cols) action)))]
    (fit-line width (append-label layout line label))))

(defn- prefix-summary [prefixes]
  (if (seq prefixes)
    (->> prefixes
         (take 6)
         (map (fn [{:keys [prefix count]}] (str "/" prefix ":" count)))
         (str/join " "))
    ""))

(defn- summary-line [{:keys [selected prefixes visible-count filter hidden undo redo]}]
  (let [selected-str (if selected
                       (str (:cidr selected) " " (:range selected) " " (:hosts selected) " hosts")
                       "no subnet selected")
        filter-str (when-not (str/blank? (str filter))
                     (str "  filter: " filter " (" visible-count ")"))
        hidden-str (when (seq hidden)
                     (str "  hidden: " (str/join "," hidden)))
        history-str (str "  undo:" (or undo 0) " redo:" (or redo 0))
        prefixes-str (prefix-summary prefixes)]
    (str selected-str
         (when (seq prefixes-str) (str "  " prefixes-str))
         filter-str
         hidden-str
         history-str)))

(defn frame
  "Returns the render frame as {:lines :width :height :mode} without ANSI wrapping."
  [{:keys [plan rows selected scroll message filter summary layout] :as state} width height]
  (let [screen-width (max 1 width)
        width (if (> screen-width 1) (dec screen-width) screen-width)
        height (max 1 height)
        all-rows (or rows (model/rows plan))
        layout (or layout (table-layout width all-rows))
        header-lines 4
        footer-lines 4
        body-height (max min-body-height (- height header-lines footer-lines))
        {:keys [rows scroll selected]} (viewport all-rows selected scroll body-height)
        mode (:mode layout)
        title (str "snetc tree: " (:parent plan) "  (" (count all-rows) " leaf subnet"
                   (when-not (= 1 (count all-rows)) "s") ")"
                   "  [" (name mode) "]"
                   (when-not (str/blank? (str filter))
                     (str "  filter:" filter)))
        table-header (table-header width layout)
        rule (apply str (repeat (min width 126) "-"))
        body (map-indexed (fn [idx row]
                            (row-line width layout (= (+ scroll idx) selected) row))
                          rows)
        help "up/down k/j  s split  J join  S split-to  H hosts  f filter  / search  : cmd  i import  e export  p print  q quit"
        summary (or summary (model/summary all-rows (nth all-rows selected nil)))
        summary (assoc summary
                       :filter filter
                       :hidden (hidden-columns mode)
                       :undo (count (:undo plan))
                       :redo (count (:redo plan)))
        msg (or message "")]
    {:width width
     :height height
     :scroll scroll
     :selected selected
     :mode mode
     :lines (vec
             (concat [(fit-line width title)
                      ""
                      (fit-line width table-header)
                      rule]
                     body
                     [(apply str (repeat width " "))
                      (fit-line width (summary-line summary))
                      (fit-line width help)
                      (fit-line width msg)]))}))

(defn full-screen
  "Wraps frame lines as a full repaint ANSI screen."
  [{:keys [lines]}]
  (str "\u001b[?25l\u001b[2J\u001b[H"
       (str/join "\r\n" lines)
       "\u001b[0m"))

(defn diff-screen
  "Returns ANSI writes needed to transform old-frame into new-frame.
  Falls back to a full repaint when dimensions are unknown or changed."
  [old-frame new-frame]
  (if (or (nil? old-frame)
          (not= (:width old-frame) (:width new-frame))
          (not= (:height old-frame) (:height new-frame)))
    (full-screen new-frame)
    (let [old-lines (:lines old-frame)
          new-lines (:lines new-frame)
          max-lines (max (count old-lines) (count new-lines))
          writes (keep (fn [idx]
                         (let [old-line (get old-lines idx "")
                               new-line (get new-lines idx "")]
                           (when (not= old-line new-line)
                             (str "\u001b[" (inc idx) ";1H" new-line "\u001b[K"))))
                       (range max-lines))]
      (str "\u001b[?25l" (apply str writes) "\u001b[0m"))))

(defn render
  "Returns a full ANSI screen for interactive planner state."
  [state width height]
  (full-screen (frame state width height)))
