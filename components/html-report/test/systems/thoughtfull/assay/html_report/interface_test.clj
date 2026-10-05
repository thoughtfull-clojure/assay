(ns systems.thoughtfull.assay.html-report.interface-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [systems.thoughtfull.assay.html-report.interface :as html-report]))

(def ^:private report
  {:workspace "<ws>"
   :generated-at "2026-10-04T00:00:00Z"
   :bricks [{:brick {:name "a" :type :component}
             :metrics {:mean-function-complexity 12 :instability 0.333333}
             :functions [{:name "f" :file "components/a/src/a.clj" :line 7
                          :complexity 12 :depth 3 :forms 40 :params 1}
                         {:name "g" :file "components/a/src/a.clj" :line 20
                          :complexity 2 :depth 2 :forms 10 :params 0}]}
            {:brick {:name "b" :type :component}
             :metrics {:mean-function-complexity 1}}]
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
   :dependency-rules {:new-dependencies :warning :stable-dependencies nil}})

(deftest render-test
  (let [html (html-report/render report)]
    (is (str/starts-with? html "<!doctype html>"))
    (is (str/includes? html "<title>Assay: &lt;ws&gt;</title>"))
    (is (str/includes? html "'Segoe UI'") "CSS is not escaped")
    (is (str/includes? html "12 is above the maximum of 10"))
    (is (str/includes? html ">0.33</td>") "decimals to two places")
    (is (str/includes? html "<pre class=\"mermaid\">graph TD\n  b0[&quot;a&quot;]")
      "dependency graph")
    (is (str/includes? html "mermaid@11.17.2"))
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
                                     :base-metrics {"a" {:mean-function-complexity 9}}
                                     :resolved [{:brick {:name "z" :type :component}
                                                 :metric :lines
                                                 :message "fixed"}]}
                   :change-thresholds {:forms [{:rule :max-increase
                                                :value 50}]})))]
    (is (str/includes? html "<code>origin/main</code>"))
    (is (str/includes? html "<code>0123456789ab</code>"))
    (is (str/includes? html
          (str "<td class=\"num\" title=\"was 9.0\">12.0"
            " <span class=\"up\">(+3.0)</span></td>"))
      "a changed brick's value shows how much it changed")
    (is (str/includes? html ">existing</span>"))
    (is (str/includes? html "<h2>Resolved</h2>"))
    (is (str/includes? html "<td>Changes (with --base)</td><td>Forms</td><td>increase ≤ 50</td>"))
    (is (not (str/includes? html "class=\"num error\""))
      "existing violations are not highlighted")))

(deftest summary-table-test
  (let [html (html-report/render report)]
    (is (str/includes? html "<th class=\"num\" title=\"Mean cyclomatic complexity of the brick&#39;s functions.\">Mean function complexity</th>"))
    (is (str/includes? html "<td class=\"num\">12.0</td>"))
    (is (str/includes? html "<tr class=\"average\"><td>Average</td>"))
    (is (str/includes? html "<td class=\"num\">6.5</td>")
      "the average of 12 and 1")))

(deftest outlier-test
  (let [html (html-report/render
               (assoc report :bricks
                 (for [[i forms] (map-indexed vector [10 10 10 10 10 10 10 10 10 100])]
                   {:brick {:name (str "b" i) :type :component}
                    :metrics {:forms forms}})))]
    (is (str/includes? html
          (str "<td class=\"num outlier\" title=\"3.0 standard deviations above"
            " the mean of all bricks (19 ± 27)\">100</td>")))
    (is (= 1 (count (re-seq #"class=\"num outlier\"" html))))
    (is (str/includes? html "Outlined: 2 or more standard deviations"))))

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
    (is (str/includes? html
          "<dt>Mean function complexity</dt><dd>The mean cyclomatic"))))

(deftest new-brick-test
  (is (str/includes?
        (html-report/render
          (assoc report :violations [] :comparison {:base-ref "main" :base-rev "0123456789ab"
                                                    :changed-bricks #{"a"} :base-metrics {}
                                                    :resolved []}))
        "component</span> <span class=\"badge status\">new</span>")
    "a changed brick missing from the base is new"))

(deftest legend-code-test
  (is (str/includes? (html-report/render report)
        "<dd><code>Ce / (Ca + Ce)</code>, from <code>0</code>")))

(deftest section-order-test
  (is (= ["Violations" "Dependencies" "Complexity" "Modularity" "Thresholds"]
        (map second (re-seq #"<h2[^>]*>([^<]+)</h2>"
                      (html-report/render report))))))

(deftest comparison-section-order-test
  (is (= ["Violations" "Resolved" "Dependencies" "Complexity" "Modularity"
          "Thresholds"]
        (map second
          (re-seq #"<h2[^>]*>([^<]+)</h2>"
            (html-report/render
              (assoc (assoc-in report [:violations 0 :status] :new)
                :comparison
                {:base-ref "main" :base-rev "0123456789ab"
                 :changed-bricks #{"a"} :base-metrics {}
                 :resolved [{:brick {:name "z" :type :component}
                             :metric :forms
                             :message "fixed"}]})))))))
