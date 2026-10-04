(ns systems.thoughtfull.assay.html-report.interface-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [systems.thoughtfull.assay.html-report.interface :as html-report]))

(def ^:private report
  {:workspace "<ws>"
   :generated-at "2026-10-04T00:00:00Z"
   :bricks [{:brick {:name "a" :type :component}
             :metrics {:max-function-complexity 12}
             :functions [{:name "f" :file "components/a/src/a.clj" :line 7
                          :complexity 12}]}]
   :violations [{:brick {:name "a" :type :component}
                 :metric :max-function-complexity
                 :level :error
                 :message "12 is above the maximum of 10"}]
   :thresholds {:max-function-complexity [{:rule :max :value 10}]}})

(deftest render-test
  (let [html (html-report/render report)]
    (is (str/starts-with? html "<!doctype html>"))
    (is (str/includes? html "<title>Assay: &lt;ws&gt;</title>"))
    (is (str/includes? html "'Segoe UI'") "CSS is not escaped")
    (is (str/includes? html "12 is above the maximum of 10"))
    (is (str/includes? html "class=\"num error\""))
    (is (str/includes? html "components/a/src/a.clj:7"))))

(deftest comparison-test
  (let [html (html-report/render
               (-> report
                 (assoc-in [:violations 0 :status] :existing)
                 (assoc :comparison {:base-ref "origin/main"
                                     :base-rev "0123456789abcdef"
                                     :changed-bricks #{"a"}
                                     :base-metrics {"a" {:max-function-complexity 9}}
                                     :resolved [{:brick {:name "z" :type :component}
                                                 :metric :lines
                                                 :message "fixed"}]}
                   :changes {:lines [{:rule :max-increase :value 50}]})))]
    (is (str/includes? html "<code>origin/main</code>"))
    (is (str/includes? html "<code>0123456789ab</code>"))
    (is (str/includes? html "9 → 12"))
    (is (str/includes? html ">existing</span>"))
    (is (str/includes? html "<h2>Resolved</h2>"))
    (is (str/includes? html "increase of at most 50"))
    (is (not (str/includes? html "class=\"num error\""))
      "existing violations are not highlighted")))
