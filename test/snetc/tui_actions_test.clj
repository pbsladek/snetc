(ns snetc.tui-actions-test
  (:require [clojure.test :refer [deftest is testing]]
            [snetc.tui-actions :as actions]))

(deftest command-parsing-test
  (testing "aliases normalize to command names"
    (is (= {:cmd "split" :args ["/26"] :arg "/26"}
           (actions/parse-command "s /26")))
    (is (= "hosts" (:cmd (actions/parse-command "h 62"))))
    (is (= "join" (:cmd (actions/parse-command "j /24"))))
    (is (= "filter" (:cmd (actions/parse-command "f @edge"))))
    (is (= "clear" (:cmd (actions/parse-command "x"))))
    (is (= "help" (:cmd (actions/parse-command "?")))))

  (testing "numeric parsers reject invalid input"
    (is (= 26 (actions/parse-prefix-input "/26")))
    (is (nil? (actions/parse-prefix-input "/33")))
    (is (= 62 (actions/parse-positive-long "62")))
    (is (nil? (actions/parse-positive-long "0")))))
