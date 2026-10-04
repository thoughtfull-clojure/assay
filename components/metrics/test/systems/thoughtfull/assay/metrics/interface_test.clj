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
          [:max-nesting-depth :max-nesting-location])))
  (is (= 1 (:max-nesting-depth (measure "[]")))))

(defn- depth
  [source]
  (:max-nesting-depth (measure source)))

(deftest binding-nesting-depth-test
  (testing "the body nests inside the let"
    ;; defn, let, (f (g x))
    (is (= 4 (depth "(defn f [] (let [x 1] (f (g x))))"))))
  (testing "binding values start again at their own depth"
    ;; (f (g (h x))) is 3 deep on its own; defn and let don't add to it
    (is (= 3 (depth "(defn f [] (let [x (f (g (h 1)))] x))")))
    (is (= 3 (depth "(let [{:keys [a]} (f (g (h 1)))] a)")))
    (is (= 1 (depth "(let [[a [b [c]]] x] a)"))
      "destructuring adds nothing"))
  (testing "other binding forms"
    (is (= 3 (depth "(loop [x (f (g (h 1)))] (recur x))")))
    (is (= 3 (depth "(when-let [x (f (g (h 1)))] x)")))
    (is (= 3 (depth "(for [x (f (g (h 1))) :let [y (f (g (h x)))]] y)")))
    (is (= 3 (depth "(letfn [(f [x] (g (h x)))] (f 1))"))
      "letfn functions start again"))
  (testing "a let without a binding vector nests normally"
    (is (= 2 (depth "(let x (f))")))))

(deftest params-nesting-depth-test
  (testing "parameter vectors add nothing"
    (is (= 1 (depth "(defn f [{:keys [a] :or {a 1}}] a)")))
    (is (= 1 (depth "(fn [[a [b]]] a)")))
    (is (= 1 (depth "(defn f \"doc\" {:m 1} [[a]] a)"))))
  (testing "vectors in the body still count"
    (is (= 3 (depth "(defn f [x] [x [x]])"))))
  (testing "each arity nests, but not its parameters"
    (is (= 3 (depth "(defn f ([[a]] (g a)) ([a b] b))"))))
  (testing "defmethod skips its dispatch value, then its parameters"
    (is (= 2 (depth "(defmethod m [:a :b] [[x]] (g x))"))))
  (testing "letfn functions skip their parameters"
    (is (= 1 (depth "(letfn [(f [{:keys [a]}] a)] 1)")))))

(deftest nesting-location-test
  (testing "points to the deepest form and names its function"
    (is (= {:file "f.clj" :line 3 :name "f"}
          (:max-nesting-location
           (measure "(defn f [x]\n  (let [y 1]\n    (g (h x))))\n(def z [1])")))))
  (testing "binding values that restart can still be the deepest"
    (is (= {:file "f.clj" :line 2 :name "f"}
          (:max-nesting-location
           (measure "(defn f []\n  (let [y (a (b (c (d 1))))]\n    y))")))))
  (testing "no name outside a function"
    (is (= {:file "f.clj" :line 1}
          (:max-nesting-location (measure "(def x [[1]])"))))))

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
                        :max-nesting-depth {:file file :line 3
                                            :name "branchy"}}}
          (dissoc (metrics/measure-brick root {:name "c" :files [file]})
            :functions)))))
