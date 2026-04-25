(ns snetc.perf-bench
  "Small repeatable performance probes for hot pure paths.
  These are intentionally dependency-free so they can run in CI and locally with
  `clojure -M -m snetc.perf-bench [size] [iterations]`."
  (:require [snetc.ip :as ip]
            [snetc.ops :as ops]
            [snetc.plan :as plan]
            [snetc.tui-bench :as tui-bench]))

(def default-size 512)
(def default-iterations 5)

(defn- parse-positive-int [s fallback]
  (try
    (let [n (Integer/parseInt (str s))]
      (if (pos? n) n fallback))
    (catch Exception _ fallback)))

(defn- cidr-at [base step idx prefix]
  (str (ip/long->ip (+ base (* step idx))) "/" prefix))

(defn- adjacent-24s [n]
  (let [base (ip/ip->long "10.0.0.0")
        step 256]
    (mapv #(cidr-at base step % 24) (range n))))

(defn- overlap-cidrs [n]
  (into ["10.0.0.0/8"] (adjacent-24s n)))

(defn- lpm-routes [n]
  (into ["10.0.0.0/8" "10.0.0.0/16"] (adjacent-24s n)))

(defn measure-case
  "Returns timing data for f after a small warmup.
  result-f receives the last return value so callers can expose a compact,
  behavior-checkable result without keeping huge benchmark outputs."
  [name iterations f result-f]
  (dotimes [_ 2] (f))
  (loop [remaining iterations
         total 0
         ret nil]
    (if (zero? remaining)
      {:name name
       :iterations iterations
       :total-nanos total
       :avg-nanos (quot total iterations)
       :result (result-f ret)}
      (let [start (System/nanoTime)
            ret (f)
            elapsed (- (System/nanoTime) start)]
        (recur (dec remaining) (+ total elapsed) ret)))))

(defn run-suite
  "Runs representative probes for core ops, planner bulk split, and TUI render paths."
  ([] (run-suite {:size default-size :iterations default-iterations}))
  ([{:keys [size iterations]
     :or {size default-size iterations default-iterations}}]
   (let [routes (lpm-routes size)
         cidrs (adjacent-24s size)
         overlaps (overlap-cidrs size)]
     [(measure-case "ops/find-overlaps"
                    iterations
                    #(ops/find-overlaps overlaps)
                    count)
      (measure-case "ops/longest-prefix-match"
                    iterations
                    #(ops/longest-prefix-match "10.0.1.42" routes)
                    identity)
      (measure-case "ops/aggregate"
                    iterations
                    #(ops/aggregate cidrs)
                    count)
      (measure-case "plan/split-leaf-to-prefix"
                    iterations
                    #(plan/split-leaf-to-prefix (plan/new-plan "10.0.0.0/16")
                                                "10.0.0.0/16"
                                                24)
                    #(count (plan/leaf-cidrs %)))
      (measure-case "tui/filter-rows"
                    iterations
                    #(tui-bench/filter-sample size "label:label-10")
                    :ret)
      (measure-case "tui/render-frame"
                    iterations
                    #(tui-bench/render-sample size)
                    #(select-keys % [:rows :ret]))])))

(defn -main [& args]
  (let [size (parse-positive-int (first args) default-size)
        iterations (parse-positive-int (second args) default-iterations)]
    (doseq [result (run-suite {:size size :iterations iterations})]
      (prn result))))
