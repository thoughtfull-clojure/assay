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

(deftest forms-test
  (testing "counts forms at every depth"
    ;; list, defn, f, [x], x, (inc x), inc, x
    (is (= 8 (:forms (measure "(defn f [x] (inc x))")))))
  (testing "ignores comments and uneval"
    (is (= 3 (:forms (measure "(f #_ignored x) ; comment")))))
  (testing "top-level forms"
    (is (= 2 (:top-level-forms (measure "(ns foo)\n#_(x)\n(def y 1)"))))))

(defn- depth
  "The nesting depth of a function, or of source wrapped in (defn t [] ...),
  which adds 1 to the body."
  [source]
  (-> (if (re-find #"^\((defn|defmethod) " source)
        source
        (str "(defn t [] " source ")"))
    measure :functions first :depth))

(deftest binding-nesting-depth-test
  (testing "the body nests inside the let"
    ;; defn, let, (f (g x))
    (is (= 4 (depth "(defn f [] (let [x 1] (f (g x))))"))))
  (testing "binding values start again at their own depth"
    ;; (f (g (h x))) is 3 deep on its own; defn and let don't add to it
    (is (= 3 (depth "(defn f [] (let [x (f (g (h 1)))] x))")))
    (is (= 3 (depth "(let [{:keys [a]} (f (g (h 1)))] a)")))
    (is (= 2 (depth "(let [[a [b [c]]] x] a)"))
      "destructuring adds nothing"))
  (testing "other binding forms"
    (is (= 3 (depth "(loop [x (f (g (h 1)))] (recur x))")))
    (is (= 3 (depth "(when-let [x (f (g (h 1)))] x)")))
    (is (= 3 (depth "(for [x (f (g (h 1))) :let [y (f (g (h x)))]] y)")))
    (is (= 3 (depth "(letfn [(f [x] (g (h x)))] (f 1))"))
      "letfn functions start again"))
  (testing "a let without a binding vector nests normally"
    (is (= 3 (depth "(let x (f))")))))

(deftest params-nesting-depth-test
  (testing "parameter vectors add nothing"
    (is (= 1 (depth "(defn f [{:keys [a] :or {a 1}}] a)")))
    (is (= 2 (depth "(fn [[a [b]]] a)")))
    (is (= 1 (depth "(defn f \"doc\" {:m 1} [[a]] a)"))))
  (testing "vectors in the body still count"
    (is (= 3 (depth "(defn f [x] [x [x]])"))))
  (testing "each arity nests, but not its parameters"
    (is (= 3 (depth "(defn f ([[a]] (g a)) ([a b] b))"))))
  (testing "defmethod skips its dispatch value, then its parameters"
    (is (= 2 (depth "(defmethod m [:a :b] [[x]] (g x))"))))
  (testing "letfn functions skip their parameters"
    (is (= 2 (depth "(letfn [(f [{:keys [a]}] a)] 1)")))))

(deftest functions-test
  (is (= [{:name "f" :file "f.clj" :line 2 :complexity 1}
          {:name "g" :file "f.clj" :line 3 :complexity 1}
          {:name "m" :file "f.clj" :line 4 :complexity 1}
          {:name "area :square" :file "f.clj" :line 5 :complexity 1}]
        (map #(select-keys % [:name :file :line :complexity])
          (:functions
           (measure (str "(ns foo)\n"
                      "(defn f [x] x)\n"
                      "(defn- g [] nil)\n"
                      "(defmacro m [x] x)\n"
                      "(defmethod area :square [s] (* s s))\n"
                      "(def not-a-function 1)\n")))))))

(deftest function-measures-test
  (let [f (fn [source]
            (-> (measure source) :functions first
              (select-keys [:depth :depth-line :forms :params])))]
    (is (= {:depth 3 :depth-line 3 :forms 10 :params 1}
          (f "(defn f\n  [x]\n  (g (h x)))")))
    (testing "a binding value that restarts can still be the deepest"
      (is (= 2 (:depth-line
                (f "(defn f []\n  (let [y (a (b (c (d 1))))]\n    y))")))))
    (testing "params counts the widest arity, without varargs"
      (is (= 3 (:params (f "(defn f ([a] a) ([a b c & more] a))")))))
    (testing "defmethod params skip the dispatch value"
      (is (= 2 (:params (f "(defmethod m [:a :b] [x y] x)")))))))

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
                      :forms 22
                      :functions 2
                      :mean-function-complexity 2.0
                      :mean-function-depth 2.0}
            :sources [{:file file :ns (quote c) :requires [] :forms 22
                       :definitions [{:name (quote simple) :line 2
                                      :references #{(quote x)}}
                                     {:name (quote branchy) :line 3
                                      :references (set (map symbol
                                                         ["x" "if" "when"]))}]}]}
          (-> (metrics/measure-brick root {:name "c" :files [file]})
            (dissoc :functions)
            (update :sources (partial mapv #(dissoc % :keywords :fragments))))))))

(deftest columns-test
  (is (= ["Files" "Forms" "Functions" "Mean function complexity"
          "Mean nesting depth"]
        (map :label (take 5 metrics/columns))))
  (is (= "2.5"
        (metrics/column-text (nth metrics/columns 3)
          {:mean-function-complexity 2.46})))
  (is (= "0.33" (metrics/format-value :instability 1/3)))
  (is (= "–" (metrics/format-value :instability nil))))

(deftest averages-test
  (is (= {:files 1.5
          :forms 15.0
          :functions 2.0
          :mean-function-complexity 4.0
          :mean-function-depth 3.0
          :afferent 1.0
          :efferent nil
          :instability 0.5
          :abstractness nil
          :cohesion nil
          :shared-keywords nil}
        (metrics/averages
          [{:brick {:type :component}
            :metrics {:files 1 :forms 10 :functions 1
                      :mean-function-complexity 6.0
                      :mean-function-depth 4.0
                      :instability 0.25}
            :functions [{:complexity 6 :depth 4}]}
           {:brick {:type :component}
            :metrics {:files 2 :forms 20 :functions 3
                      :mean-function-complexity 2.0
                      :mean-function-depth 2.0
                      :instability 0.75 :afferent 1}
            :functions [{:complexity 1 :depth 2} {:complexity 2 :depth 2}
                        {:complexity 3 :depth 2}]}]))
    "the mean of each brick's value, skipping bricks without one")
  (is (= "1.5" (metrics/format-value :files 1.5)))
  (is (= "2" (metrics/format-value :files 2.0))))

(deftest outliers-test
  (let [bricks (fn [& forms]
                 (map-indexed (fn [i n]
                                {:brick {:name (str "b" i)}
                                 :metrics {:forms n :abstractness nil}})
                   forms))]
    (testing "values at least k standard deviations from the mean, either way"
      (let [o (metrics/outliers (bricks 10 10 10 10 10 10 10 10 10 100) 2)]
        (is (= [["b9" :forms]] (keys o)))
        (is (= 3.0 (:z (o ["b9" :forms]))))
        (is (= 19.0 (:mean (o ["b9" :forms])))))
      (is (= [["b9" :forms]]
            (keys (metrics/outliers (bricks 100 100 100 100 100 100 100 100
                                      100 10)
                    2)))))
    (testing "no outliers without variation or with too few values"
      (is (empty? (metrics/outliers (bricks 5 5 5 5) 2)))
      (is (empty? (metrics/outliers (bricks 1 100) 0.5))))
    (testing "bases don't count for metrics that only describe components"
      (let [measurements (concat
                           (for [i (range 9)]
                             {:brick {:name (str "c" i) :type :component}
                              :metrics {:abstractness 0.9 :forms 10}})
                           [{:brick {:name "c9" :type :component}
                             :metrics {:abstractness 0.8 :forms 10}}
                            {:brick {:name "base" :type :base}
                             :metrics {:abstractness 0.0 :forms 100}}])
            o (metrics/outliers measurements 2)]
        (is (= #{["c9" :abstractness] ["base" :forms]} (set (keys o))))
        (is (= :components (:peers (o ["c9" :abstractness]))))
        (is (= :bricks (:peers (o ["base" :forms]))))
        (is (= 0.89 (Double/parseDouble
                      (format "%.2f" (:abstractness (metrics/averages
                                                      measurements))))))))))

(deftest definitions-test
  (is (= [{:name 'labels :line 1 :references #{'metrics/metrics 'into}}
          {:name 'f :line 2 :references #{'labels}}]
        (:definitions
         (measure "(def ^:private labels (into {} metrics/metrics))\n(defn ^:private f [] labels)"))))
  (is (= "f" (-> (measure "(defn ^:private f [] 1)") :functions first :name))
    "metadata isn't part of a function's name"))

(deftest keywords-test
  (is (= #{:id :name :x/qualified}
        (:keywords (measure (str "(ns foo (:require [a :as b]))\n"
                              "(defn f [{:keys [id]}] {:id id :name 1"
                              " :x/qualified 2})"))))
    "data keywords only: not the ns form or syntax like :keys"))

(deftest fragments-test
  (let [big (str "(let [a 1 b 2 c 3 d 4 e 5] (+ a b c d e) (* a b c d e))")
        {:keys [fragments]} (measure (str "(defn f []\n  " big ")\n"
                                       "(defn g [] ; different layout\n"
                                       "  (let [a 1 b 2 c 3 d 4 e 5]\n"
                                       "    (+ a b c d e)\n"
                                       "    (* a b c d e)))"))
        by-hash (group-by :hash fragments)
        same (first (filter #(= 2 (count %)) (vals by-hash)))]
    (is same "the same code in different layouts hashes the same")
    (is (= [2 4] (map :line same)))
    (is (every? #(>= (:forms %) 20) fragments))))

(deftest label-test
  (is (= "Forms" (metrics/label {:metric :forms})))
  (is (= "Parameters" (metrics/label {:scope :function :metric :params})))
  (is (= "Custom" (metrics/label {:metric :forms :label "Custom"})))
  (is (= "mystery" (metrics/label {:metric :mystery}))))
