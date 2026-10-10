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
    (thresholds/check {:brick-thresholds {:m rules}} measurements)))

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
                 " other bricks (5 ± 0.82), over the limit of 2")]]
            (check [{:rule :std-devs :value 2 :level :warning}] measurements))))
    (testing "reports the stats and the computed limit"
      (let [[v] (thresholds/check {:brick-thresholds {:m [{:rule :std-devs :value 2}]}}
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

(deftest unknown-rule-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown threshold rule"
        (thresholds/check {:brick-thresholds {:m [{:rule :median :value 1}]}}
          [(measurement "a" 1)]))))

(deftest types-test
  (is (= [["a" :warning "0.2 is below the minimum of 0.5"]]
        (check [{:rule :min :value 0.5 :level :warning :types #{:component}}]
          [(measurement "a" 0.2) (measurement "cli" :base 0)]))))

(deftest function-thresholds-test
  (let [measurements [{:brick {:name "a" :type :component}
                       :functions [{:name "f" :file "a.clj" :line 3
                                    :complexity 12 :depth 9 :depth-line 7}
                                   {:name "g" :file "a.clj" :line 20
                                    :complexity 2 :depth 2 :depth-line 21}]}]
        violations (thresholds/check
                     {:function-thresholds
                      {:complexity [{:rule :max :value 10}]
                       :depth [{:rule :max :value 8 :level :warning}]}}
                     measurements)]
    (is (= [[:function "f" :complexity :error {:file "a.clj" :line 3 :name "f"}
             "12 is above the maximum of 10"]
            [:function "f" :depth :warning {:file "a.clj" :line 7 :name "f"}
             "9 is above the maximum of 8"]]
          (map (juxt :scope :subject :metric :level :location :message)
            violations))))
  (testing "rejects statistical rules"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"support only :max and :min"
          (thresholds/check
            {:function-thresholds {:complexity [{:rule :std-devs :value 2}]}}
            [])))))

(deftest merge-config-test
  (let [merged (thresholds/merge-config
                 {:brick-thresholds {:abstractness []}
                  :function-thresholds {:forms [{:rule :max :value 1}]}})]
    (is (= [] (get-in merged [:brick-thresholds :abstractness])))
    (is (= [{:rule :max :value 1}] (get-in merged [:function-thresholds :forms])))
    (is (= (get-in thresholds/default-config [:function-thresholds :complexity])
          (get-in merged [:function-thresholds :complexity])))))

(deftest worse-level-test
  (is (= :error (thresholds/worse-level :warning :error)))
  (is (= :warning (thresholds/worse-level nil :warning)))
  (is (nil? (thresholds/worse-level nil nil))))

(deftest function-subject-test
  (is (= ["a.core/f"]
        (map :subject
          (thresholds/check {:function-thresholds
                             {:complexity [{:rule :max :value 1}]}}
            [{:brick {:name "a" :type :component}
              :functions [{:name "f" :ns 'a.core :complexity 2}
                          {:name "f" :ns 'a.interface :complexity 1}]}])))
    "a function's subject is its namespace-qualified name"))

(deftest by-severity-test
  (is (= [:big-error :small-error :big-warning :no-value]
        (map :id
          (thresholds/by-severity
            [{:id :no-value :level :warning}
             {:id :big-warning :level :warning :value 40 :limit 10}
             {:id :small-error :level :error :value 11 :limit 10}
             {:id :big-error :level :error :value 0.1 :limit 0.5}])))))
