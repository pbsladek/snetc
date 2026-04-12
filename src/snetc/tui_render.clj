(ns snetc.tui-render
  "Pure rendering helpers for the interactive subnet planner."
  (:require [clojure.string :as str]
            [snetc.ip :as ip]
            [snetc.plan :as plan]
            [snetc.subnet :as subnet]))

(def min-body-height 1)
(def max-subnet-width 18)
(def max-range-width 32)

(defn- clip [s width]
  (let [s (str s)]
    (cond
      (<= width 0) ""
      (<= (count s) width) s
      :else (subs s 0 width))))

(defn- left [width s]
  (format (str "%-" width "s") (clip s width)))

(defn- right [width n]
  (format (str "%" width "d") n))

(defn- range-label [cidr]
  (let [[start end] (subnet/cidr->range cidr)]
    (str (ip/long->ip start) ".." (ip/long->ip end))))

(defn- usable-label [{:keys [first-host last-host]}]
  (str first-host ".." last-host))

(defn- action-label [can-split? can-join?]
  (cond
    (and can-split? can-join?) "s/j"
    can-split? "s"
    can-join? "j"
    :else "-"))

(defn rows
  "Returns precomputed visible-row data for plan."
  [planner]
  (mapv (fn [idx {:keys [cidr depth label]}]
          (let [info (subnet/subnet-info cidr)
                can-split? (plan/can-split? planner cidr)
                can-join? (plan/can-join? planner cidr)]
            {:idx (inc idx)
             :cidr cidr
             :depth depth
             :label label
             :mask (:mask info)
             :range (range-label cidr)
             :usable (usable-label info)
             :hosts (:hosts info)
             :can-split? can-split?
             :can-join? can-join?
             :action (action-label can-split? can-join?)}))
        (range)
        (plan/leaves planner)))

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
  (let [cols (column-widths rows)]
    {:mode (cond
             (<= (mode-width :full cols) width) :full
             (<= (mode-width :standard cols) width) :standard
             (<= (mode-width :compact cols) width) :compact
             :else :tiny)
     :cols cols}))

(defn- with-label [width line label]
  (let [label (str label)
        remaining (- width (count line))]
    (if (and (> remaining 1) (seq label))
      (str line " " (clip label (dec remaining)))
      line)))

(defn- table-header [width {:keys [mode cols]}]
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
    (with-label width line "Lbl")))

(defn- row-line [width {:keys [mode cols]} selected? {:keys [idx cidr label mask range usable hosts action]}]
  (let [marker (if selected? ">" " ")
        label (or label "")
        line (case mode
               :full
               (str marker " " (right 3 idx) "  "
                    (left (:subnet cols) cidr) "  "
                    (left (:mask cols) mask) "  "
                    (left (:range cols) range) "  "
                    (left (:usable cols) usable) "  "
                    (right (:hosts cols) hosts) "  "
                    (left (:action cols) action))

               :standard
               (str marker " " (right 3 idx) "  "
                    (left (:subnet cols) cidr) "  "
                    (left (:mask cols) mask) "  "
                    (left (:range cols) range) "  "
                    (right (:hosts cols) hosts) "  "
                    (left (:action cols) action))

               :compact
               (str marker " " (right 3 idx) "  "
                    (left (:subnet cols) cidr) "  "
                    (left (:range cols) range) "  "
                    (right (:hosts cols) hosts) " "
                    (left (:action cols) action))

               :tiny
               (str marker " " (right 3 idx) "  "
                    (left (:subnet cols) cidr) " "
                    (right (:hosts cols) hosts) " "
                    (left (:action cols) action)))]
    (fit-line width (with-label width line label))))

(defn render
  "Returns a full ANSI screen for interactive planner state."
  [{:keys [plan selected scroll message]} width height]
  (let [screen-width (max 1 width)
        width (if (> screen-width 1) (dec screen-width) screen-width)
        height (max 1 height)
        all-rows (rows plan)
        layout (table-layout width all-rows)
        header-lines 4
        footer-lines 3
        body-height (max min-body-height (- height header-lines footer-lines))
        {:keys [rows scroll selected]} (viewport all-rows selected scroll body-height)
        title (str "snetc tree: " (:parent plan) "  (" (count all-rows) " leaf subnet"
                   (when-not (= 1 (count all-rows)) "s") ")")
        table-header (table-header width layout)
        rule (apply str (repeat (min width 126) "-"))
        body (map (fn [row]
                    (row-line width layout (= (:idx row) (inc selected)) row))
                  rows)
        help "up/down or k/j select  s/enter split  J/backspace join  l label  u undo  r redo  e export  p print  q quit"
        msg (or message "")]
    (str "\u001b[?25l\u001b[2J\u001b[H"
         (str/join "\r\n"
                   (concat [(fit-line width title)
                            ""
                            (fit-line width table-header)
                            rule]
                           body
                           [(apply str (repeat width " "))
                            (fit-line width help)
                            (fit-line width msg)]))
         "\u001b[0m")))
