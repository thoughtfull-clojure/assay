(ns systems.thoughtfull.assay.text-report.interface-test
  (:require
   [clojure.test :refer [deftest is]]
   [systems.thoughtfull.assay.text-report.interface :as text-report]))

(def ^:private violations
  [{:brick {:name "b" :type :base :dir "bases/b"}
    :metric :cohesion
    :level :warning
    :message "0.1 is too low"
    :status :new}
   {:brick {:name "a" :type :component :dir "components/a"}
    :kind :function
    :metric :function-complexity
    :level :error
    :value 12
    :limit 10
    :function-line 7
    :subject "a/f"
    :message "12 is above the maximum of 10"
    :location {:file "components/a/src/a.clj" :line 7 :name "f"}
    :status :new}
   {:brick {:name "a" :type :component :dir "components/a"}
    :kind :function
    :metric :function-depth
    :level :warning
    :value 9
    :limit 8
    :function-line 7
    :subject "a/f"
    :message "9 is above the maximum of 8"
    :location {:file "components/a/src/a.clj" :line 9 :name "f"}
    :status :new}
   {:brick {:name "c" :type :component :dir "components/c"}
    :metric :mean-function-complexity
    :level :error
    :message "old news"
    :status :existing}])

(deftest render-test
  (is (= (str "Complexity\n"
           "error   components/a/src/a.clj:7  f: complexity 12 > 10,"
           " depth 9 > 8 (line 9)\n"
           "\n"
           "Modularity\n"
           "warning bases/b  Cohesion 0.1 is too low\n"
           "\n"
           "assay: 3 bricks, 1 new error, 1 new warning, compared with HEAD"
           " (1 not new, not shown)\n")
        (text-report/render {:bricks [{} {} {}]
                             :violations violations
                             :comparison {:base-ref "HEAD"}}))
    "a heading for each section, and a line for each finding: a
    function's violations are one line"))

(deftest render-without-comparison-test
  (is (= "assay: 1 brick, 0 errors, 0 warnings\n"
        (text-report/render {:bricks [{}] :violations []}))))

(deftest hidden-warnings-test
  (is (= "assay: 2 bricks, 0 errors, 17 warnings hidden (--warnings to show)\n"
        (text-report/render {:bricks [{} {}] :violations []
                             :hidden-warnings 17}))))

(deftest hidden-existing-test
  (is (= "assay: 1 brick, 0 new errors, 0 new warnings, compared with HEAD (3 not new, not shown)\n"
        (text-report/render {:bricks [{}] :violations []
                             :hidden-existing 3
                             :comparison {:base-ref "HEAD"}}))))
