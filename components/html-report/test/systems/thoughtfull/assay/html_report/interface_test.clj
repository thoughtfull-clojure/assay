(ns systems.thoughtfull.assay.html-report.interface-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [systems.thoughtfull.assay.html-report.interface :as html-report]))

(def ^:private report
  {:workspace "<ws>"
   :generated-at "2026-10-04T00:00:00Z"
   :bricks [{:brick {:name "a" :type :component}
             :metrics {:max-function-complexity 12 :instability 0.333333}
             :functions [{:name "f" :file "components/a/src/a.clj" :line 7
                          :complexity 12 :depth 3 :forms 40 :params 1}
                         {:name "g" :file "components/a/src/a.clj" :line 20
                          :complexity 2 :depth 2 :forms 10 :params 0}]}
            {:brick {:name "b" :type :component}
             :metrics {:max-function-complexity 1}}]
   :edges [{:from "a" :to "b" :interface "b"}]
   :violations [{:scope :function
                 :brick {:name "a" :type :component}
                 :metric :complexity
                 :subject "f"
                 :level :error
                 :message "12 is above the maximum of 10"
                 :location {:file "components/a/src/a.clj" :line 7 :name "f"}}]
   :thresholds {:brick-thresholds {:abstractness [{:rule :min :value 0.5
                                                   :types #{:component}}]}
                :function-thresholds {:complexity [{:rule :max :value 10}]}}
   :dependency-rules {:cycles :error :stable-dependencies nil}})

(deftest render-test
  (let [html (html-report/render report)]
    (is (str/starts-with? html "<!doctype html>"))
    (is (str/includes? html "<title>Assay: &lt;ws&gt;</title>"))
    (is (str/includes? html "'Segoe UI'") "CSS is not escaped")
    (is (str/includes? html "12 is above the maximum of 10"))
    (is (str/includes? html "<td class=\"num error\" title=\"12 is above the maximum of 10\">12</td>")
      "the function's complexity cell is highlighted")
    (is (str/includes? html ">0.33</td>") "decimals to two places")
    (is (str/includes? html "component</span></td><td>b</td><td></td>")
      "dependencies")
    (is (str/includes? html "<td>Bricks</td><td>Abstractness</td><td>≥ 0.5 (components)</td>"))
    (is (str/includes? html "<td>Functions</td><td>Complexity</td><td>≤ 10</td>"))
    (is (= 1 (count (re-seq #"<h2>Thresholds</h2>" html))) "one thresholds heading")
    (is (str/includes? html ">off</td>") "a rule turned off")
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
                   :change-thresholds {:forms [{:rule :max-increase
                                                :value 50}]})))]
    (is (str/includes? html "<code>origin/main</code>"))
    (is (str/includes? html "<code>0123456789ab</code>"))
    (is (str/includes? html "9 → 12"))
    (is (str/includes? html ">existing</span>"))
    (is (str/includes? html "<h2>Resolved</h2>"))
    (is (str/includes? html "<td>Changes (with --base)</td><td>Forms</td><td>increase ≤ 50</td>"))
    (is (not (str/includes? html "class=\"num error\""))
      "existing violations are not highlighted")))

(deftest summary-table-test
  (let [html (html-report/render report)]
    (is (str/includes? html "<th class=\"num\" title=\"Mean / max cyclomatic complexity of the brick&#39;s functions.\">Function complexity</th>"))
    (is (str/includes? html "<td class=\"num\">– / 12</td>")
      "mean and max share a column")
    (is (str/includes? html "<tr class=\"total\"><td>Total</td>"))))

(deftest hidden-warnings-test
  (let [html (html-report/render (assoc report :violations []
                                   :hidden-warnings 4))]
    (is (str/includes? html "<p class=\"none\">No errors.</p>"))
    (is (str/includes? html
          "<div class=\"n\">4</div><div class=\"l\">warnings hidden (--warnings to show)</div>"))))

(deftest legend-test
  (let [html (html-report/render report)]
    (is (str/includes? html
          "<details class=\"legend\"><summary>What these metrics mean</summary>")
      "collapsed: no open attribute")
    (is (str/includes? html "<dt>Function complexity</dt><dd>The mean and maximum"))
    (is (str/includes? html "<dt>Parameters</dt><dd>Positional parameters"))))

(deftest legend-code-test
  (is (str/includes? (html-report/render report)
        "<dd><code>Ce / (Ca + Ce)</code>, from <code>0</code>")))

(deftest same-name-functions-test
  (let [html (html-report/render
               (assoc report
                 :bricks [{:brick {:name "a" :type :component}
                           :metrics {}
                           :functions [{:name "analyze" :ns 'a.core
                                        :file "a/core.clj" :line 1
                                        :complexity 12 :depth 1 :forms 9
                                        :params 1}
                                       {:name "analyze" :ns 'a.interface
                                        :file "a/interface.clj" :line 1
                                        :complexity 1 :depth 1 :forms 5
                                        :params 1}]}]
                 :violations [{:scope :function
                               :brick {:name "a" :type :component}
                               :metric :complexity
                               :subject "a.core/analyze"
                               :level :error
                               :message "12 is above the maximum of 10"}]))]
    (is (= 1 (count (re-seq #"class=\"num error\"" html)))
      "only the offending analyze is highlighted, not its namesake")))
