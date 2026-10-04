(ns systems.thoughtfull.assay.thresholds.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [systems.thoughtfull.assay.thresholds.interface :as thresholds]))

(defn- measurement
  ([name value]
   (measurement name :component value))
  ([name type value]
   {:brick {:name name :type type}
    :metrics {:m value}}))

(defn- check
  [rules measurements]
  (map (juxt (comp :name :brick) :level :message)
    (thresholds/check {:m rules} measurements)))

(deftest max-min-test
  (is (= [["b" :error "11 is above the maximum of 10"]
          ["c" :warning "1 is below the minimum of 2"]]
        (check [{:rule :max :value 10}
                {:rule :min :value 2 :level :warning}]
          [(measurement "a" 5) (measurement "b" 11) (measurement "c" 1)]))))

(deftest std-devs-test
  (let [measurements (conj (mapv #(measurement (str "c" %) %) [4 5 6 5])
                       (measurement "outlier" 20))]
    (testing "compares each brick with the others, excluding itself"
      (is (= [["outlier" :warning
               (str "20 is 18.4 standard deviations above the mean of 4"
                 " other bricks (5 ± 0.8), over the limit of 2")]]
            (check [{:rule :std-devs :value 2 :level :warning}] measurements))))
    (testing "reports the stats and the computed limit"
      (let [[v] (thresholds/check {:m [{:rule :std-devs :value 2}]}
                  measurements)]
        (is (= 4 (get-in v [:stats :peers])))
        (is (< 6.63 (:limit v) 6.64)))))
  (testing "compares only bricks of the same type"
    (is (empty? (check [{:rule :std-devs :value 3}]
                  [(measurement "a" 1) (measurement "b" 5)
                   (measurement "c" 3) (measurement "d" 3)
                   (measurement "base" :base 100)]))))
  (testing "skips with too few peers"
    (is (empty? (check [{:rule :std-devs :value 1}]
                  [(measurement "a" 1) (measurement "b" 2)
                   (measurement "c" 100)])))
    (is (seq (check [{:rule :std-devs :value 1 :min-peers 2}]
               [(measurement "a" 1) (measurement "b" 2)
                (measurement "c" 100)]))))
  (testing "identical peers"
    (is (= [["d" :error "2 is above every other brick (1)"]]
          (check [{:rule :std-devs :value 2}]
            [(measurement "a" 1) (measurement "b" 1)
             (measurement "c" 1) (measurement "d" 2)])))))

(deftest location-test
  (is (= {:file "f.clj" :line 3 :name "g"}
        (:location
         (first
           (thresholds/check {:m [{:rule :max :value 1}]}
             [(assoc (measurement "a" 5)
                :locations {:m {:file "f.clj" :line 3 :name "g"}})]))))))

(deftest unknown-rule-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown threshold rule"
        (thresholds/check {:m [{:rule :median :value 1}]}
          [(measurement "a" 1)]))))

(deftest merge-thresholds-test
  (let [merged (thresholds/merge-thresholds {:lines []
                                             :forms [{:rule :max :value 1}]})]
    (is (= [] (:lines merged)))
    (is (= [{:rule :max :value 1}] (:forms merged)))
    (is (= (:max-function-complexity thresholds/default-thresholds)
          (:max-function-complexity merged)))))
