(ns systems.thoughtfull.assay.baseline.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [systems.thoughtfull.assay.baseline.interface :as baseline]))

(defn- brick
  [name metrics]
  {:brick {:name name :type :component :dir (str "components/" name)}
   :metrics metrics})

(defn- violation
  [name metric]
  {:brick {:name name :type :component}
   :metric metric
   :rule {:rule :max :value 1}
   :level :error})

(deftest changed-bricks-test
  (is (= #{"a"}
        (baseline/changed-bricks [(brick "a" {}) (brick "b" {})]
          #{"components/a/src/a/core.clj"
            "components/b/test/b/core_test.clj"
            "README.md"}))))

(deftest compare-reports-test
  (let [base {:bricks [(brick "a" {:m 5}) (brick "b" {:m 5})
                       (brick "c" {:m 5})]
              :violations [(violation "a" :m) (violation "c" :m)]}
        head {:bricks [(brick "a" {:m 6}) (brick "b" {:m 20})
                       (brick "c" {:m 5}) (brick "d" {:m 30})]
              :violations [(violation "a" :m) (violation "b" :m)
                           (violation "d" :m) (violation "e" :m)]}
        changed #{"components/a/src/a.clj" "components/b/src/b.clj"
                  "components/d/src/d.clj"}
        result (baseline/compare-reports base head changed
                 {:changes {:m [{:rule :max-increase :value 10
                                 :level :warning}]}})
        by-status (fn [status]
                    (->> (:violations result)
                      (filter #(= status (:status %)))
                      (map (juxt (comp :name :brick) :base-value :change?))
                      set))]
    (testing "existing violations"
      (is (= #{["a" 5 nil]} (by-status :existing))))
    (testing "new violations in changed bricks, and change rules"
      (is (= #{["b" 5 nil] ["d" nil nil] ["b" 5 true] ["d" nil true]}
            (by-status :new))))
    (testing "new violations in unchanged bricks are indirect"
      (is (= #{["e" nil nil]} (by-status :indirect))))
    (testing "resolved violations"
      (is (= ["c"] (map (comp :name :brick)
                     (get-in result [:comparison :resolved])))))
    (testing "change messages"
      (is (= #{"increased by 15 (5 to 20), above the maximum increase of 10"
               "increased by 30 in a new brick, above the maximum increase of 10"}
            (set (keep :message (:violations result))))))))

(deftest historical-test
  (is (= [:existing]
        (map :status
          (:violations
           (baseline/compare-reports {:bricks [] :violations []}
             {:bricks [(brick "a" {})]
              :violations [(assoc (violation "a" :co-change)
                             :historical? true)]}
             #{"components/a/src/a.clj"} {}))))
    "a violation from history is never new, even in a changed brick"))

(deftest max-increase-percent-test
  (let [check (fn [base-m head-m]
                (->> (baseline/compare-reports
                       {:bricks [(brick "a" {:m base-m})]}
                       {:bricks [(brick "a" {:m head-m})]}
                       #{"components/a/src/a.clj"}
                       {:changes {:m [{:rule :max-increase-percent
                                       :value 50}]}})
                  :violations
                  (map :message)))]
    (is (= ["increased by 60% (10 to 16), above the maximum increase of 50%"]
          (check 10 16)))
    (is (empty? (check 10 15)))
    (is (empty? (check 0 15)))))

(deftest function-violation-test
  (let [f-violation (fn [subject]
                      {:scope :function
                       :brick {:name "a" :type :component}
                       :metric :complexity
                       :rule {:rule :max :value 10}
                       :subject subject
                       :level :error})
        base {:bricks [{:brick {:name "a"}
                        :functions [{:name "old" :complexity 11}]}]
              :violations [(f-violation "old")]}
        head {:bricks [(brick "a" {})]
              :violations [(assoc (f-violation "old") :location {:line 99})
                           (f-violation "new")]}
        result (baseline/compare-reports base head #{"components/a/src/a.clj"}
                 {})]
    (is (= [["old" :existing 11] ["new" :new nil]]
          (map (juxt :subject :status :base-value) (:violations result)))
      "a function is matched by name, even when its line moves")))

(deftest new-dependencies-test
  (let [base {:bricks [] :edges [{:from "a" :to "b"}]}
        head {:bricks [(brick "a" {})]
              :edges [{:from "a" :to "b"}
                      {:from "a" :to "c-impl" :interface "c"
                       :location {:file "f.clj" :line 4}}]}
        changed #{"components/a/src/a.clj"}]
    (is (= [["a" "c-impl" :warning :new {:file "f.clj" :line 4}
             "now depends on c-impl (through interface c)"]]
          (map (juxt (comp :name :brick) :subject :level :status :location
                 :message)
            (:violations (baseline/compare-reports base head changed
                           {:new-dependencies :warning})))))
    (is (empty? (:violations (baseline/compare-reports base head changed {}))))))
