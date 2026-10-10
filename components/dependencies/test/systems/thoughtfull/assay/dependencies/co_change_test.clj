(ns systems.thoughtfull.assay.dependencies.co-change-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [systems.thoughtfull.assay.dependencies.interface :as dependencies]))

(defn- brick
  [name]
  {:brick {:name name :type :component :dir (str "components/" name)}
   :metrics {}
   :sources []})

(defn- commit
  [& names]
  (set (for [n names] (str "components/" n "/src/" n ".clj"))))

(def ^:private bricks
  (mapv brick ["a" "b" "c" "d" "e" "f" "g"]))

(def ^:private commits
  ;; a and b change together 5 times and a twice alone: 5 of b's 5
  ;; commits. c and d change together 5 times, but c depends on d. e and f
  ;; change together 4 times. A reformat touches every brick.
  (concat
    (repeat 5 (commit "a" "b"))
    (repeat 2 (commit "a"))
    (repeat 5 (commit "c" "d"))
    (repeat 4 (commit "e" "f"))
    [(commit "a" "b" "c" "d" "e" "f" "g")
     #{"README.md"}]))

(def ^:private settings
  {:min-shared 5 :max-bricks-per-commit 5})

(defn- co-change
  [settings commits]
  (:co-change
   (dependencies/findings {:co-change settings}
     {:bricks bricks
      :edges [{:from "c" :to "d"}]
      :commits commits})))

(deftest co-change-test
  (let [findings (co-change settings commits)]
    (testing "only pairs without a dependency path, with enough commits"
      (is (= [["b" "a" 1.0]]
            (map (juxt (comp :name :brick) :subject :value) findings))))
    (testing "the message counts the less changed brick's commits"
      (is (= "with a in 5 of its 5 commits (100%), though neither depends on the other"
            (:message (first findings))))
      (is (:historical? (first findings))))
    (testing "commits that touch too many bricks don't count"
      (is (empty? (co-change (assoc settings :max-bricks-per-commit 1)
                    commits))))
    (testing ":min-shared"
      (is (= #{"b" "e"}
            (set (map (comp :name :brick)
                   (co-change (assoc settings :min-shared 4) commits))))))
    (testing "off without settings, and nothing without history"
      (is (nil? (:co-change (dependencies/findings {} {:bricks bricks}))))
      (is (empty? (co-change settings nil))))))

(deftest transitive-dependency-test
  (is (empty? (:co-change
               (dependencies/findings {:co-change settings}
                 {:bricks bricks
                  :edges [{:from "a" :to "g"} {:from "g" :to "b"}]
                  :commits (repeat 5 (commit "a" "b"))})))
    "a depends on b through g"))
