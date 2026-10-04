(ns systems.thoughtfull.assay.metrics-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [systems.thoughtfull.assay.metrics :as metrics]
   [systems.thoughtfull.assay.parse :as parse]))

(defn- measure
  [metric s]
  (metric (parse/parse-string s)))

(deftest top-level-form-count-test
  (testing "empty source"
    (is (= 0 (measure metrics/top-level-form-count ""))))
  (testing "ignores comments, whitespace, and uneval"
    (is (= 2 (measure metrics/top-level-form-count
               "; comment\n(ns foo)\n#_(ignored)\n(defn f [] 1)\n")))))

(deftest form-count-test
  (testing "counts forms at every depth"
    ;; (defn f [x] (inc x)) => list, defn, f, [x], x, (inc x), inc, x
    (is (= 8 (measure metrics/form-count "(defn f [x] (inc x))"))))
  (testing "ignores comments and uneval"
    (is (= 3 (measure metrics/form-count "(f #_ignored x) ; comment")))))
