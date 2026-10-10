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
   :kind :brick
   :direction :max
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
        result (baseline/compare-reports base head changed)
        by-status (fn [status]
                    (->> (:violations result)
                      (filter #(= status (:status %)))
                      (map (juxt (comp :name :brick) :base-value))
                      set))]
    (testing "existing violations"
      (is (= #{["a" 5]} (by-status :existing))))
    (testing "new violations in changed bricks"
      (is (= #{["b" 5] ["d" nil]} (by-status :new))))
    (testing "new violations in unchanged bricks are indirect"
      (is (= #{["e" nil]} (by-status :indirect))))
    (testing "resolved violations"
      (is (= ["c"] (map (comp :name :brick)
                     (get-in result [:comparison :resolved])))))))

(deftest historical-test
  (is (= [:existing]
        (map :status
          (:violations
           (baseline/compare-reports {:bricks [] :violations []}
             {:bricks [(brick "a" {})]
              :violations [(assoc (violation "a" :co-change)
                             :historical? true)]}
             #{"components/a/src/a.clj"}))))
    "a violation from history is never new, even in a changed brick"))

(deftest function-violation-test
  (let [f-violation (fn [subject]
                      {:kind :function
                       :direction :max
                       :brick {:name "a" :type :component}
                       :metric :function-complexity
                       :subject subject
                       :level :error})
        base {:bricks [{:brick {:name "a"}
                        :functions [{:name "old" :complexity 11}]}]
              :violations [(f-violation "old")]}
        head {:bricks [(brick "a" {})]
              :violations [(assoc (f-violation "old") :location {:line 99})
                           (f-violation "new")]}
        result (baseline/compare-reports base head #{"components/a/src/a.clj"})]
    (is (= [["old" :existing 11] ["new" :new nil]]
          (map (juxt :subject :status :base-value) (:violations result)))
      "a function is matched by name, even when its line moves")))

(deftest worsened-function-test
  (let [f-violation (fn [subject value]
                      {:kind :function
                       :direction :max
                       :brick {:name "a" :type :component}
                       :metric :function-complexity
                       :subject subject
                       :value value
                       :level :error
                       :message (str value " is above the maximum of 10")})
        base {:bricks [{:brick {:name "a"}
                        :functions [{:name "worse" :complexity 11}
                                    {:name "same" :complexity 12}
                                    {:name "better" :complexity 14}]}]
              :violations [(f-violation "worse" 11) (f-violation "same" 12)
                           (f-violation "better" 14)]}
        head {:bricks [(brick "a" {})]
              :violations [(f-violation "worse" 13) (f-violation "same" 12)
                           (f-violation "better" 13)]}
        result (baseline/compare-reports base head #{"components/a/src/a.clj"})]
    (is (= [["worse" :new "13 is above the maximum of 10 (was 11)"]
            ["same" :existing "12 is above the maximum of 10"]
            ["better" :existing "13 is above the maximum of 10"]]
          (map (juxt :subject :status :message) (:violations result)))
      "a change that makes a function's violation worse makes it new")))

(deftest new-edges-test
  (let [base {:bricks [] :edges [{:from "a" :to "b"}]}
        head {:bricks [(brick "a" {})]
              :edges [{:from "a" :to "b"}
                      {:from "a" :to "c-impl" :interface "c"}]}
        result (baseline/compare-reports base head #{"components/a/src/a.clj"})]
    (is (= [nil true] (map :new? (:edges result)))
      "an edge base doesn't have is marked new")
    (is (empty? (:violations result)) "a new edge isn't a violation")))
