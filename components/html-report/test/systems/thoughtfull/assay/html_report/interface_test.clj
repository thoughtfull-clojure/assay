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
   :rules {:dependency-rules {:stable-dependencies nil}}})

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
                                                 :message "fixed"}]})))]
    (is (str/includes? html "<code>origin/main</code>"))
    (is (str/includes? html "<code>0123456789ab</code>"))
    (is (str/includes? html
          (str "<td class=\"num\" title=\"was 9.0\">12.0"
            " <span class=\"up\">(+3.0)</span></td>"))
      "a changed brick's value shows how much it changed")
    (is (str/includes? html ">existing</span>"))
    (is (str/includes? html "<h2>Resolved</h2>"))
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

(deftest graph-viewer-test
  (let [html (html-report/render report)]
    (is (str/includes? html "<div class=\"graph\"><div class=\"graph-controls\">")
      "the graph sits in a viewer with controls")
    (is (= #{"zoom-in" "zoom-out" "fit" "fullscreen"}
          (set (map second (re-seq #"data-action=\"([a-z-]+)\"" html)))))
    (is (str/includes? html "graph.querySelector('pre.mermaid svg')")
      "the viewer pans and zooms the graph, not the button icons")))

(deftest graph-violations-test
  (is (str/includes?
        (html-report/render
          (assoc report :graph-violations
            [{:metric :co-change :brick {:name "a"} :subject "b"}]))
        "b0 -.- b1")
    "the graph draws violations the table leaves out"))

(deftest shared-libraries-test
  (let [html (html-report/render
               (assoc report :libraries
                 [{:library "next.jdbc" :bricks ["a" "b"]}
                  {:library "clojure.tools.cli" :bricks ["x"]}]))]
    (is (str/includes? html
          (str "<td><code>next.jdbc</code></td><td class=\"num\">2</td>"
            "<td>a, b</td>")))
    (is (not (str/includes? html "clojure.tools.cli"))
      "libraries only one brick requires aren't listed")))

(deftest new-brick-test
  (is (str/includes?
        (html-report/render
          (assoc report :violations [] :comparison {:base-ref "main" :base-rev "0123456789ab"
                                                    :changed-bricks #{"a"} :base-metrics {}
                                                    :resolved []}))
        "component</span> <span class=\"badge status\">new</span>")
    "a changed brick missing from the base is new"))

(deftest base-rows-test
  (let [html (html-report/render
               (update report :bricks conj
                 {:brick {:name "cli" :type :base}
                  :metrics {:afferent 0 :efferent 2 :instability 1.0
                            :abstractness nil :cohesion 0.25}}))
        section (fn [heading]
                  (second (re-find (re-pattern (str "(?s)<h2>" heading
                                                 "</h2>(.*?)<h2>"))
                            html)))]
    (is (not (str/includes? (section "Dependencies")
               "<span class=\"brick-name\">cli</span> <span class=\"type\">base</span></td><td class"))
      "the dependencies table leaves bases out")
    (is (str/includes? (section "Modularity")
          (str "<span class=\"brick-name\">cli</span> <span class=\"type\">"
            "base</span></td><td class=\"num\">–</td>"
            "<td class=\"num\">0.25</td>"))
      "a base has no abstractness, but has cohesion")))

(deftest rule-groups-test
  (let [html (html-report/render
               (assoc report :rules
                 {:dependency-rules {:stable-dependencies :error}
                  :io-rules {:mutable-state :warning}
                  :error-handling-rules {:broad-catch :warning}
                  :test-rules {:test-boundary :warning}}))]
    (is (= ["Dependencies" "Complexity" "Modularity" "I/O and mutability"
            "Error handling" "Tests"]
          (map second
            (re-seq #"<td class=\"cat cat-[a-z]+\" rowspan=\"\d+\">([^<]+)</td>"
              html)))
      "the thresholds table is grouped by category, in section order")
    (is (str/includes? html
          (str "<td class=\"cat cat-complexity\" rowspan=\"1\">Complexity</td>"
            "<td>Functions</td><td>Complexity</td><td>≤ 10</td>"))
      "a category's cell spans its rows")
    (is (str/includes? html
          (str "<td class=\"cat cat-modularity\" rowspan=\"1\">Modularity"
            "</td><td>Bricks</td><td>Abstractness</td>")))
    (is (str/includes? html
          (str "<td class=\"cat cat-errors\" rowspan=\"1\">Error handling"
            "</td><td>Bricks</td><td>Broad catch</td>")))))

(deftest section-colors-test
  (let [html (html-report/render report)]
    (is (str/includes? html
          "<td class=\"cat cat-complexity\">Complexity</td><td>12 is above")
      "a violation's metric is tinted with its section's color")
    (is (str/includes? html
          "<div class=\"section cat-dependencies\"><h2>Dependencies</h2>")
      "each section, its heading and table, has its color")))

(deftest legend-code-test
  (is (str/includes? (html-report/render report)
        "<dd><code>Ce / (Ca + Ce)</code>, from <code>0</code>")))

(deftest section-order-test
  (is (= ["Violations" "Dependencies" "Complexity" "Modularity"
          "I/O and mutability" "Error handling" "Tests" "Thresholds"]
        (map second (re-seq #"<h2[^>]*>([^<]+)</h2>"
                      (html-report/render report))))))

(deftest comparison-section-order-test
  (is (= ["Violations" "Resolved" "Dependencies" "Complexity" "Modularity"
          "I/O and mutability" "Error handling" "Tests" "Thresholds"]
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

(def ^:private compared
  (assoc report
    :violations []
    :hidden-existing 2
    :comparison {:base-ref "main" :base-rev "0123456789ab"
                 :changed-bricks #{"a"}
                 :base-metrics {"a" {:mean-function-complexity 9}
                                "b" {:mean-function-complexity 1}}
                 :resolved []}))

(deftest changed-rows-test
  (let [html (html-report/render compared)]
    (is (str/includes? html
          "<td class=\"num\" title=\"was 9.0\">12.0")
      "a brick whose value changed is shown")
    (is (not (str/includes? html "<td class=\"num\">1.0</td>"))
      "a brick whose values didn't change is not")
    (is (str/includes? html "<td class=\"num\">6.5</td>")
      "the average is still of all bricks")
    (is (str/includes? html
          "1 unchanged brick not shown. The average is of all bricks."))
    (is (str/includes? html
          "No brick changed in this section. The average is of all 2 bricks.")
      "a section with no changes")))

(deftest hidden-existing-test
  (let [html (html-report/render
               (assoc compared :violations
                 [(assoc (first (:violations report)) :status :new)]))]
    (is (str/includes? html
          "2 existing or indirect violations not shown"))
    (is (not (str/includes? html "<th>Status</th>"))
      "every violation shown is new"))
  (is (str/includes? (html-report/render compared)
        "<p class=\"none\">No new violations.</p>")))

(deftest collapsed-violations-test
  (let [violations (for [i (range 22)]
                     {:brick {:name (str "b" i) :type :component}
                      :metric :forms
                      :level :warning
                      :value (+ 151 i)
                      :limit 150
                      :message (str "forms " (+ 151 i))})
        html (html-report/render (assoc report :violations violations))
        [shown more] (str/split html #"<summary>2 more violations</summary>")]
    (is (some? more))
    (is (< (str/index-of shown "forms 172") (str/index-of shown "forms 153")))
    (is (str/includes? more "forms 152"))
    (is (str/includes? more "forms 151"))
    (is (not (str/includes? shown "forms 151")))))
