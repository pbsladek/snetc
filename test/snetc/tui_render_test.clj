(ns snetc.tui-render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [snetc.plan :as plan]
            [snetc.tui-render :as render]))

(def ansi-pattern #"\u001B\[[0-9;?]*[ -/]*[@-~]")

(defn- visible-lines [screen]
  (str/split-lines (str/replace screen ansi-pattern "")))

(defn- bare-newline? [screen]
  (boolean (re-find #"(^|[^\r])\n" screen)))

(defn- deeply-split-plan []
  (reduce (fn [planner cidr] (plan/split-leaf planner cidr))
          (plan/new-plan "10.0.0.0/16")
          ["10.0.0.0/16"
           "10.0.0.0/17"
           "10.0.0.0/18"
           "10.0.0.0/19"
           "10.0.0.0/20"
           "10.0.0.0/21"
           "10.0.0.0/22"
           "10.0.0.0/23"
           "10.0.0.0/24"
           "10.0.0.0/25"]))

(deftest rows-test
  (testing "rows contain display-ready subnet details"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/split-leaf "10.0.0.0/24"))
          [row] (render/rows planner)]
      (is (= "10.0.0.0/25" (:cidr row)))
      (is (= "255.255.255.128" (:mask row)))
      (is (= "10.0.0.0..10.0.0.127" (:range row)))
      (is (= "10.0.0.1..10.0.0.126" (:usable row)))
      (is (= 126 (:hosts row)))
      (is (= "s/j" (:action row))))))

(deftest viewport-test
  (testing "selected row is kept in view"
    (let [rows (mapv (fn [idx] {:idx (inc idx) :cidr (str "10.0." idx ".0/24")})
                     (range 10))]
      (is (= 0 (:scroll (render/viewport rows 0 0 4))))
      (is (= 2 (:scroll (render/viewport rows 5 0 4))))
      (is (= 6 (:scroll (render/viewport rows 9 0 4)))))))

(deftest render-test
  (testing "rendered screen includes title and commands"
    (let [screen (render/render {:plan (plan/new-plan "10.0.0.0/24")
                                 :selected 0
                                 :scroll 0
                                 :message "Ready"}
                                100
                                20)]
      (is (re-find #"snetc tree: 10\.0\.0\.0/24" screen))
      (is (re-find #"up/down" screen))
      (is (re-find #"Ready" screen)))))

(deftest frame-and-diff-test
  (testing "frame exposes layout mode and summary lines"
    (let [frame (render/frame {:plan (plan/new-plan "10.0.0.0/24")
                               :selected 0
                               :scroll 0
                               :message "Ready"}
                              120
                              12)
          text (str/join "\n" (:lines frame))]
      (is (= :full (:mode frame)))
      (is (str/includes? text "[full]"))
      (is (str/includes? text "/24:1"))
      (is (str/includes? text "undo:0 redo:0"))))

  (testing "active filter is visible in the title"
    (let [frame (render/frame {:plan (plan/new-plan "10.0.0.0/24")
                               :selected 0
                               :scroll 0
                               :filter "@edge"
                               :message "Ready"}
                              80
                              12)]
      (is (str/includes? (first (:lines frame)) "filter:@edge"))))

  (testing "hidden columns are reported in compact layouts"
    (let [frame (render/frame {:plan (plan/new-plan "10.0.0.0/24")
                               :selected 0
                               :scroll 0
                               :message "Ready"}
                              70
                              12)
          text (str/join "\n" (:lines frame))]
      (is (seq (render/hidden-columns (:mode frame))))
      (is (str/includes? text "hidden:"))))

  (testing "diff-screen writes changed lines instead of clearing unchanged frames"
    (let [old-frame (render/frame {:plan (plan/new-plan "10.0.0.0/24")
                                   :selected 0 :scroll 0 :message "one"}
                                  80 12)
          new-frame (render/frame {:plan (plan/new-plan "10.0.0.0/24")
                                   :selected 0 :scroll 0 :message "two"}
                                  80 12)
          diff (render/diff-screen old-frame new-frame)]
      (is (str/includes? diff "\u001b["))
      (is (not (str/includes? diff "\u001b[2J")))
      (is (str/includes? diff "two")))))

(deftest cached-rows-test
  (testing "render uses precomputed rows from state when present"
    (let [screen (render/render {:plan (plan/new-plan "10.0.0.0/24")
                                 :rows [{:idx 1
                                         :cidr "10.9.0.0/24"
                                         :label "cached"
                                         :mask "255.255.255.0"
                                         :range "10.9.0.0..10.9.0.255"
                                         :usable "10.9.0.1..10.9.0.254"
                                         :hosts 254
                                         :action "s"}]
                                 :selected 0
                                 :scroll 0
                                 :message "Ready"}
                                100
                                20)]
      (is (str/includes? screen "10.9.0.0/24"))
      (is (str/includes? screen "cached")))))

(deftest raw-mode-line-ending-test
  (testing "rendered screens use CRLF so raw terminal mode returns to column one"
    (let [screen (render/render {:plan (plan/new-plan "10.0.0.0/24")
                                 :selected 0
                                 :scroll 0
                                 :message "Ready"}
                                100
                                20)]
      (is (str/includes? screen "\r\n"))
      (is (false? (bare-newline? screen))))))

(deftest render-width-test
  (testing "rendered lines stay within the terminal width"
    (let [planner (deeply-split-plan)]
      (doseq [width [50 80 100 120]]
        (let [screen (render/render {:plan planner
                                     :selected 0
                                     :scroll 0
                                     :message "Split 10.0.0.0/20"}
                                    width
                                    24)]
          (is (every? #(<= (count %) width) (visible-lines screen))
              (str "line exceeds width " width)))))))

(deftest clipped-label-test
  (testing "long labels are clipped with a visible marker instead of silently disappearing"
    (let [planner (-> (plan/new-plan "10.0.0.0/24")
                      (plan/label-leaf "10.0.0.0/24" "alphabetical-routing-boundary"))
          screen (render/render {:plan planner
                                 :selected 0
                                 :scroll 0
                                 :message "Ready"}
                                80
                                12)
          row (first (filter #(str/includes? % "10.0.0.0/24")
                             (filter #(str/starts-with? % ">")
                                     (visible-lines screen))))]
      (is (str/includes? row "alphabetic>"))
      (is (<= (count row) 80)))))

(deftest deep-tree-format-test
  (testing "deeply split plans keep subnet addresses plain and left-aligned"
    (let [screen (render/render {:plan (deeply-split-plan)
                                 :selected 0
                                 :scroll 0
                                 :message "Split 10.0.0.0/25"}
                                126
                                24)
          lines (visible-lines screen)]
      (is (some #(str/includes? % ">   1  10.0.0.0/26") lines))
      (is (not-any? #(re-find #"\sd\d+\s+10\.0\." %) lines))
      (is (not-any? #(str/includes? % "| 10.0.") lines))
      (is (not-any? #(str/includes? % "..10.0.0.0/26") lines))
      (is (some #(str/includes? % "Usable IPs") lines))
      (is (not-any? #(str/includes? % "~") lines))
      (is (every? #(<= (count %) 126) lines)))))

(deftest full-ipv4-range-format-test
  (testing "full-width rendering leaves room for maximum IPv4 range labels"
    (let [screen (render/render {:plan (plan/new-plan "255.255.255.255/32")
                                 :selected 0
                                 :scroll 0
                                 :message "Ready"}
                                126
                                12)
          lines (visible-lines screen)]
      (is (some #(str/includes? % "255.255.255.255..255.255.255.255  255.255.255.255..255.255.255.255") lines))
      (is (not-any? #(str/includes? % "~") lines))
      (is (every? #(<= (count %) 126) lines)))))
