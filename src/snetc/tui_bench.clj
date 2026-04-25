(ns snetc.tui-bench
  "Lightweight benchmark helpers for TUI rendering paths.
  These helpers intentionally avoid strict timing thresholds in tests; they
  provide comparable measurements for local development and regression checks."
  (:require [clojure.string :as str]
            [snetc.ip :as ip]
            [snetc.plan :as plan]
            [snetc.tui-model :as model]
            [snetc.tui-render :as render]))

(defn synthetic-rows [n]
  (let [base (ip/ip->long "10.0.0.0")]
    (mapv (fn [idx]
          (let [addr (+ base idx)
                ip-str (ip/long->ip addr)
                cidr (str ip-str "/32")
                label (when (zero? (mod idx 10)) (str "label-" idx))]
            {:idx (inc idx)
             :cidr cidr
             :cidr-lower (str/lower-case cidr)
             :label label
             :label-lower (str/lower-case (or label ""))
             :mask "255.255.255.255"
             :range (str ip-str ".." ip-str)
             :start addr
             :end addr
             :usable (str ip-str ".." ip-str)
             :hosts 1
             :prefix 32
             :can-split? false
             :can-join? false
             :action "-"}))
        (range n))))

(defn measure-nanos [f]
  (let [start (System/nanoTime)
        ret (f)]
    {:nanos (- (System/nanoTime) start)
     :ret ret}))

(defn render-sample
  "Measures full frame rendering for n synthetic rows."
  [n]
  (let [planner (plan/new-plan "10.0.0.0/8")
        rows (synthetic-rows n)]
    (-> (measure-nanos #(render/frame {:plan planner
                                       :rows rows
                                       :selected 0
                                       :scroll 0
                                       :message "bench"}
                                      120
                                      30))
        (update :ret select-keys [:width :height :mode])
        (assoc :rows n))))

(defn layout-sample
  "Measures table layout selection for n synthetic rows."
  [n]
  (let [rows (synthetic-rows n)]
    (-> (measure-nanos #(render/layout 119 rows))
        (update :ret select-keys [:mode :label-width])
        (assoc :rows n))))

(defn filter-sample
  "Measures cached row filtering for n synthetic rows."
  [n query]
  (let [rows (synthetic-rows n)]
    (-> (measure-nanos #(model/filter-rows rows query))
        (update :ret count)
        (assoc :rows n :query query))))

(defn diff-sample
  "Measures diff rendering between two nearby frames for n synthetic rows."
  [n]
  (let [planner (plan/new-plan "10.0.0.0/8")
        rows (synthetic-rows n)
        old-frame (render/frame {:plan planner :rows rows :selected 0 :scroll 0 :message "bench"}
                                120 30)
        new-frame (render/frame {:plan planner :rows rows :selected 1 :scroll 0 :message "bench"}
                                120 30)]
    (-> (measure-nanos #(render/diff-screen old-frame new-frame))
        (update :ret count)
        (assoc :rows n))))
