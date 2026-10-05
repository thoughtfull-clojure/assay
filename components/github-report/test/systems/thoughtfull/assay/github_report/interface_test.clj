(ns systems.thoughtfull.assay.github-report.interface-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [systems.thoughtfull.assay.github-report.interface :as github-report]))

(def ^:private report
  {:workspace "ws"
   :bricks [{:brick {:name "a" :type :component}
             :metrics {:mean-function-complexity 12 :forms 40}}
            {:brick {:name "b" :type :base}
             :metrics {:mean-function-complexity 2 :forms 300}}]
   :violations [{:brick {:name "a" :type :component}
                 :metric :mean-function-complexity
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
            "title=component a%3A Mean function complexity::"
            "Mean function complexity 12 is above the maximum of 10 (f)")
          (str "::warning title=base b%3A Forms::"
            "Forms 300 is 50%25%0Ahigh")]
        (github-report/annotations report))))

(deftest summary-test
  (let [summary (github-report/summary report)]
    (is (str/starts-with? summary "## Assay: ws\n\n2 bricks, 1 errors, 1 warnings."))
    (is (str/includes? summary "| ❌ error | component a | Mean function complexity |"))
    (is (str/includes? summary "**12.0** ❌"))
    (is (str/includes? summary "**300** ⚠️"))))

(def ^:private compared
  (-> report
    (assoc-in [:violations 0 :status] :new)
    (assoc-in [:violations 1 :status] :existing)
    (assoc :comparison {:base-ref "origin/main"
                        :base-rev "0123456789abcdef"
                        :changed-bricks #{"a"}
                        :base-metrics {"a" {:mean-function-complexity 4
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
    (is (str/includes? summary "| **12.0 (+8.0)** ❌ |")
      "a changed brick's value shows how much it changed")
    (is (not (str/includes? summary "**300**"))
      "existing violations are not highlighted")
    (is (str/includes? summary "300 is 50%<br>high"))))

(deftest hidden-warnings-test
  (is (str/includes?
        (github-report/summary (assoc report
                                 :violations (subvec (:violations report) 0 1)
                                 :hidden-warnings 5))
        "2 bricks, 1 errors, 5 warnings hidden (`--warnings` to show).")))

(deftest outlier-test
  (let [summary (github-report/summary
                  (assoc report
                    :violations []
                    :bricks (for [[i forms] (map-indexed vector
                                              [10 10 10 10 10 10 10 10 10 100])]
                              {:brick {:name (str "b" i) :type :component}
                               :metrics {:forms forms}})))]
    (is (str/includes? summary "| **100** |"))
    (is (= 1 (count (re-seq #"\*\*\d+\*\*" summary))))
    (is (str/includes? summary "Bold: 2 or more standard deviations"))))

(deftest shared-libraries-test
  (is (str/includes?
        (github-report/summary
          (assoc report :libraries [{:library "next.jdbc" :bricks ["a" "b"]}]))
        "**Shared libraries**\n\n| Library | Bricks | Required by |")))

(deftest average-row-test
  (is (str/includes? (github-report/summary report) "| **Average** |")))

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
    (is (= ["Violations" "Dependencies" "Complexity" "Modularity"
            "I/O and mutability" "Error handling" "Tests"]
          sections))
    (is (str/includes? summary "```mermaid\ngraph TD\n  b0[\"a\"]"))
    (is (= 6 (count (re-seq #"<details><summary>What these metrics mean" summary)))
      "a legend in each section")))

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
  (is (= ["Violations" "Resolved" "Dependencies" "Complexity" "Modularity"
          "I/O and mutability" "Error handling" "Tests"]
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

(deftest no-violations-test
  (is (str/includes? (github-report/summary (assoc report :violations []))
        "### Violations\n\nNo thresholds exceeded."))
  (is (str/includes? (github-report/summary (assoc report :violations []
                                              :hidden-warnings 3))
        "### Violations\n\nNo errors.")))
