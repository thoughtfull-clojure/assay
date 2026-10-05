(ns systems.thoughtfull.assay.text-report.interface-test
  (:require
   [clojure.test :refer [deftest is]]
   [systems.thoughtfull.assay.text-report.interface :as text-report]))

(def ^:private violations
  [{:brick {:name "b" :type :base :dir "bases/b"}
    :metric :forms
    :level :warning
    :message "300 is too many"
    :status :new}
   {:brick {:name "a" :type :component :dir "components/a"}
    :metric :max-function-complexity
    :level :error
    :message "12 is above the maximum of 10"
    :location {:file "components/a/src/a.clj" :line 7 :name "f"}
    :status :new}
   {:brick {:name "c" :type :component :dir "components/c"}
    :metric :forms
    :level :error
    :message "old news"
    :status :existing}])

(deftest render-test
  (is (= (str "error   components/a/src/a.clj:7  Max function complexity 12 is"
           " above the maximum of 10 (f)\n"
           "warning bases/b  Forms 300 is too many\n"
           "assay: 3 bricks, 1 new error, 1 new warning, compared with HEAD"
           " (1 not new, not shown)\n")
        (text-report/render {:bricks [{} {} {}]
                             :violations violations
                             :comparison {:base-ref "HEAD"}}))))

(deftest render-without-comparison-test
  (is (= "assay: 1 brick, 0 errors, 0 warnings\n"
        (text-report/render {:bricks [{}] :violations []}))))

(deftest hidden-warnings-test
  (is (= "assay: 2 bricks, 0 errors, 17 warnings hidden (--warnings to show)\n"
        (text-report/render {:bricks [{} {}] :violations []
                             :hidden-warnings 17}))))
