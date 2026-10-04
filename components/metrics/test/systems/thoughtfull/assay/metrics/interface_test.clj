(ns systems.thoughtfull.assay.metrics.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [systems.thoughtfull.assay.metrics.interface :as metrics]))

(defn- measure
  [source]
  (metrics/measure-source "f.clj" source))

(defn- complexity
  [source]
  (-> (measure source) :functions first :complexity))

(deftest lines-test
  (is (= 2 (:lines (measure "; comment\n\n(ns foo)\n  ;; more\n(def x 1)\n")))))

(deftest forms-test
  (testing "counts forms at every depth"
    ;; list, defn, f, [x], x, (inc x), inc, x
    (is (= 8 (:forms (measure "(defn f [x] (inc x))")))))
  (testing "ignores comments and uneval"
    (is (= 3 (:forms (measure "(f #_ignored x) ; comment")))))
  (testing "top-level forms"
    (is (= 2 (:top-level-forms (measure "(ns foo)\n#_(x)\n(def y 1)"))))))

(deftest nesting-depth-test
  (is (= 0 (:max-nesting-depth (measure ""))))
  (is (= {:max-nesting-depth 3
          :max-nesting-location {:file "f.clj" :line 2}}
        (select-keys (measure "(a)\n(b [c {:d 1}])")
          [:max-nesting-depth :max-nesting-location]))))

(deftest functions-test
  (is (= [{:name "f" :file "f.clj" :line 2 :complexity 1}
          {:name "g" :file "f.clj" :line 3 :complexity 1}
          {:name "m" :file "f.clj" :line 4 :complexity 1}
          {:name "area :square" :file "f.clj" :line 5 :complexity 1}]
        (:functions
         (measure (str "(ns foo)\n"
                    "(defn f [x] x)\n"
                    "(defn- g [] nil)\n"
                    "(defmacro m [x] x)\n"
                    "(defmethod area :square [s] (* s s))\n"
                    "(def not-a-function 1)\n"))))))

(deftest cyclomatic-complexity-test
  (testing "a function without branches"
    (is (= 1 (complexity "(defn f [x] (println x) (inc x))"))))
  (testing "one-branch forms"
    (is (= 2 (complexity "(defn f [x] (if x 1 2))")))
    (is (= 4 (complexity
               "(defn f [x] (when x (if-let [y x] (when-not y 1))))"))))
  (testing "namespaced special forms"
    (is (= 2 (complexity "(defn f [x] (clojure.core/when x 1))"))))
  (testing "cond counts clauses, less a final :else"
    (is (= 4 (complexity "(defn f [x] (cond (a) 1 (b) 2 (c) 3))")))
    (is (= 3 (complexity "(defn f [x] (cond (a) 1 (b) 2 :else 3))"))))
  (testing "case counts clauses, not the default"
    (is (= 3 (complexity "(defn f [x] (case x 1 :a 2 :b :c))"))))
  (testing "condp counts clauses"
    (is (= 3 (complexity "(defn f [x] (condp = x 1 :a 2 :b :c))"))))
  (testing "and and or count each argument after the first"
    (is (= 3 (complexity "(defn f [a b c] (or (and a b) c))")))
    (is (= 4 (complexity "(defn f [a b c] (or a b c d))"))))
  (testing "catch"
    (is (= 3 (complexity
               "(defn f [] (try (g) (catch A _ 1) (catch B _ 2)))"))))
  (testing "for modifiers"
    (is (= 3 (complexity
               "(defn f [xs] (for [x xs :when (odd? x) :while x] x))"))))
  (testing "extra arities"
    (is (= 2 (complexity "(defn f ([x] (f x 1)) ([x y] (+ x y)))"))))
  (testing "nested fns count toward the enclosing function"
    (is (= 2 (complexity "(defn f [xs] (map (fn [x] (when x 1)) xs))")))))

(deftest measure-brick-test
  (let [root (doto (java.io.File/createTempFile "assay" "")
               (.delete)
               (.mkdirs))
        file "components/c/src/c.clj"]
    (doto (java.io.File. root file)
      (-> .getParentFile .mkdirs)
      (spit (str "(ns c)\n"
              "(defn simple [x] x)\n"
              "(defn branchy [x] (if x (when x 1) 2))\n")))
    (is (= {:brick {:name "c" :files [file]}
            :metrics {:files 1
                      :lines 3
                      :top-level-forms 3
                      :forms 22
                      :functions 2
                      :cyclomatic-complexity 4
                      :max-function-complexity 3
                      :max-nesting-depth 3}
            :locations {:max-function-complexity
                        {:file file :line 3 :name "branchy"}
                        :max-nesting-depth {:file file :line 3}}}
          (dissoc (metrics/measure-brick root {:name "c" :files [file]})
            :functions)))))
