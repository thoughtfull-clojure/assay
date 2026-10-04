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
                 {:m [{:rule :max-increase :value 10 :level :warning}]})
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

(deftest max-increase-percent-test
  (let [check (fn [base-m head-m]
                (->> (baseline/compare-reports
                       {:bricks [(brick "a" {:m base-m})]}
                       {:bricks [(brick "a" {:m head-m})]}
                       #{"components/a/src/a.clj"}
                       {:m [{:rule :max-increase-percent :value 50}]})
                  :violations
                  (map :message)))]
    (is (= ["increased by 60% (10 to 16), above the maximum increase of 50%"]
          (check 10 16)))
    (is (empty? (check 10 15)))
    (is (empty? (check 0 15)))))
