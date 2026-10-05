(ns systems.thoughtfull.assay.github-report.interface-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [systems.thoughtfull.assay.github-report.interface :as github-report]))

(def ^:private report
  {:workspace "ws"
   :bricks [{:brick {:name "a" :type :component}
             :metrics {:max-function-complexity 12 :forms 40}}
            {:brick {:name "b" :type :base}
             :metrics {:max-function-complexity 2 :forms 300}}]
   :violations [{:brick {:name "a" :type :component}
                 :metric :max-function-complexity
                 :level :error
                 :message "12 is above the maximum of 10"
                 :location {:file "components/a/src/a.clj" :line 7
                            :name "f"}}
                {:brick {:name "b" :type :base}
                 :metric :forms
                 :level :warning
                 :message "300 is 50%\nhigh"}]})

(deftest annotations-test
  (is (= [(str "::error file=components/a/src/a.clj,line=7,"
            "title=component a%3A Max function complexity::"
            "Max function complexity 12 is above the maximum of 10 (f)")
          (str "::warning title=base b%3A Forms::"
            "Forms 300 is 50%25%0Ahigh")]
        (github-report/annotations report))))

(deftest summary-test
  (let [summary (github-report/summary report)]
    (is (str/starts-with? summary "## Assay: ws\n\n2 bricks, 1 errors, 1 warnings."))
    (is (str/includes? summary "| ❌ error | component a | Max function complexity |"))
    (is (str/includes? summary "**– / 12** ❌"))
    (is (str/includes? summary "**300** ⚠️"))))

(def ^:private compared
  (-> report
    (assoc-in [:violations 0 :status] :new)
    (assoc-in [:violations 1 :status] :existing)
    (assoc :comparison {:base-ref "origin/main"
                        :base-rev "0123456789abcdef"
                        :changed-bricks #{"a"}
                        :base-metrics {"a" {:max-function-complexity 4
                                            :forms 40}}
                        :resolved []})))

(deftest comparison-annotations-test
  (let [[new existing] (github-report/annotations compared)]
    (is (str/starts-with? new "::error "))
    (is (str/starts-with? existing "::notice "))
    (is (str/ends-with? existing "[existing]"))))

(deftest comparison-summary-test
  (let [summary (github-report/summary compared)]
    (is (str/includes? summary
          (str "2 bricks, 1 new errors, 0 new warnings. Compared with"
            " `origin/main` (merge-base `0123456789ab`), 1 bricks changed.")))
    (is (str/includes? summary "### Changed bricks"))
    (is (str/includes? summary "4 → 12 (+8)"))
    (is (not (str/includes? summary "**300**"))
      "existing violations are not highlighted")
    (is (str/includes? summary "300 is 50%<br>high"))))

(deftest hidden-warnings-test
  (is (str/includes?
        (github-report/summary (assoc report
                                 :violations (subvec (:violations report) 0 1)
                                 :hidden-warnings 5))
        "2 bricks, 1 errors, 5 warnings hidden (`--warnings` to show).")))

(deftest total-row-test
  (is (str/includes? (github-report/summary report) "| **Total** |")))

(deftest sections-test
  (let [summary (github-report/summary
                  (-> report
                    (assoc-in [:bricks 0 :functions]
                      [{:name "f" :file "components/a/src/a.clj" :line 7
                        :complexity 12 :depth 3 :forms 40 :params 1}])
                    (update :violations conj
                      {:scope :function
                       :brick {:name "a" :type :component}
                       :metric :complexity
                       :subject "f"
                       :level :error
                       :message "12 is above the maximum of 10"})
                    (assoc :edges [{:from "a" :to "b"}])))
        sections (map second (re-seq #"(?m)^### (.*)$" summary))]
    (is (= ["Violations" "Functions" "Dependencies" "Bricks"] sections))
    (is (str/includes? summary
          "| `f` | a | **12** ❌ | 3 | 40 | 1 | `components/a/src/a.clj:7` |")
      "the function's offending value is highlighted")
    (is (str/includes? summary "```mermaid\ngraph TD\n  b0[\"a\"]"))
    (is (= 2 (count (re-seq #"<details><summary>What these metrics mean" summary)))
      "a legend after the bricks and after the functions")
    (is (str/includes? summary "</details>\n\n### Dependencies")
      "the functions legend closes before the dependencies section")
    (is (str/includes? summary "| Parameters | Positional parameters"))))

(deftest brick-annotation-test
  (is (= [(str "::warning file=components/a/deps.edn,line=1,"
            "title=component a%3A Mean function complexity::"
            "Mean function complexity 9 is too high")]
        (github-report/annotations
          {:violations [{:brick {:name "a" :type :component
                                 :dir "components/a"}
                         :metric :mean-function-complexity
                         :level :warning
                         :message "9 is too high"}]}))
    "a violation of a whole brick is anchored to the brick's deps.edn"))

(deftest comparison-section-order-test
  (is (= ["Violations" "Resolved" "Functions" "Dependencies" "Changed bricks"
          "Bricks"]
        (map second (re-seq #"(?m)^### (.*)$"
                      (github-report/summary
                        (-> compared
                          (assoc-in [:bricks 0 :functions]
                            [{:name "f" :complexity 1 :depth 1 :forms 5
                              :params 0}])
                          (assoc :edges [{:from "a" :to "b"}])
                          (assoc-in [:comparison :resolved]
                            [{:brick {:name "z" :type :component}
                              :metric :forms :message "fixed"}]))))))))
