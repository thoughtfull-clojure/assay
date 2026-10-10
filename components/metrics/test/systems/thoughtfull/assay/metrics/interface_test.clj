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

(deftest reader-macro-nesting-depth-test
  (testing "reader macros that wrap a form add nothing"
    (is (= 3 (depth "(f @(g x))")))
    (is (= 3 (depth "(f '(g x))")))
    (is (= 4 (depth "(f `(g ~(h x)))")) "defn, f, g, h: only the lists count")
    (is (= 2 (depth "^{:a {:b {:c 1}}} (f x)"))
      "metadata is not part of the form")))

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
  (testing "case counts once, however many clauses, plus its bodies"
    (is (= 2 (complexity "(defn f [x] (case x 1 :a 2 :b :c))")))
    (is (= 2 (complexity
               "(defn f [x] (case x 1 :a 2 :b 3 :c 4 :d (5 6 7) :e :f))")))
    (is (= 3 (complexity "(defn f [x] (case x 1 (when x :a) :b))"))))
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
            :metrics {:forms 22
                      :functions 2
                      :definitions 2
                      :mean-function-complexity 2.0
                      :mean-function-depth 2.0
                      :mutable-state 0
                      :untyped-errors 0
                      :broad-catches 0
                      :interop-density 0.0
                      :tests 0
                      :assertions-per-test nil
                      :forms-per-test nil
                      :isolation-hazards 0
                      :hazard-kinds {}
                      :test-ratio 0.0}
            :tests []
            :sources [{:file file :ns (quote c) :requires [] :forms 22
                       :definitions [{:name (quote simple) :line 2
                                      :references #{(quote x)}
                                      :private? false
                                      :throws? false}
                                     {:name (quote branchy) :line 3
                                      :references (set (map symbol
                                                         ["x" "if" "when"]))
                                      :private? false
                                      :throws? false}]}]}
          (-> (metrics/measure-brick root {:name "c" :files [file]})
            (dissoc :functions)
            (update :sources (partial mapv #(dissoc % :keywords :fragments
                                              :mutable-state :throws
                                              :catches))))))))

(deftest interop-test
  (is (= 9 (:interop
            (measure (str "(ns n (:import (java.io File)))\n"
                       "(defn f [s] (.length s) (.-x s) (File. s) (new File s)\n"
                       "  (Math/abs -1) java.io.File/separator (.. s trim length)\n"
                       "  (String/.length s))\n"
                       "(defn g [m] (str/join m) (inc m) (Foo/bar m) (a. m))\n"))))
    "a static member of any class counts; str/join, inc, and a. don't"))

(deftest measure-test-source-test
  (let [t (metrics/measure-test-source "t.clj"
            (str "(ns t (:require [clojure.test :refer [deftest is are]]\n"
              "  [a.interface :as a]))\n"
              "(def state (atom {}))\n"
              "(deftest one (is (= 1 (a/f))) (is (a/g)))\n"
              "(deftest ^:slow two\n"
              "  (with-redefs [a/f (constantly 2)] (Thread/sleep 10)\n"
              "    (are [x] (pos? x) 1 2)))\n"))]
    (is (= [{:name 'one :line 4 :forms 14 :assertions 2}
            {:name 'two :line 5 :forms 24 :assertions 1}]
          (:tests t)))
    (is (= [[3 :atom] [6 :sleep] [6 :with-redefs]]
          (sort (map (juxt :line :kind) (:hazards t)))))
    (is (contains? (:references t) 'a/g))))

(deftest error-handling-test
  (let [source (measure (str "(ns n (:require [cognitect.anomalies"
                          " :as-alias anom]))\n"
                          "(defn a [] (throw (ex-info \"x\" {:type ::bad})))\n"
                          "(defn b [] (throw (ex-info \"x\" {:command 1})))\n"
                          "(defn c [m] (throw (ex-info \"x\" m)))\n"
                          "(defn d [] (throw (IllegalStateException. \"x\")))\n"
                          "(defn e [] (try (a) (catch Exception ex (throw ex))\n"
                          "  (catch clojure.lang.ExceptionInfo _ nil)))\n"
                          "(defn f [] (a))\n"
                          "(defn g [] (throw (ex-info \"x\""
                          " {::anom/category ::anom/fault})))\n"))]
    (is (= [:typed :untyped :unknown :java :rethrow :typed]
          (map :kind (:throws source))))
    (is (= [["Exception" true] ["clojure.lang.ExceptionInfo" false]]
          (map (juxt :class :broad?) (:catches source))))
    (is (= [true true true true true false true]
          (map :throws? (:definitions source)))
      "f only calls a definition that throws"))
  (testing "slingshot's throw+"
    (let [source (measure (str "(defn a [] (throw+ {:type :bad}))\n"
                            "(defn b [] (throw+ {:code 1}))\n"
                            "(defn c [e] (throw+ e))\n"))]
      (is (= [:typed :untyped :rethrow] (map :kind (:throws source))))
      (is (every? :throws? (:definitions source)))))
  (testing "what a catch does with what it catches"
    (is (= [:rethrows :rethrows :logs :continues]
          (map :handling
            (:catches
             (measure (str "(try (f)\n"
                        "  (catch Exception e (throw (ex-info \"x\" {} e)))\n"
                        "  (catch Throwable e (throw+ {:type :x}))\n"
                        "  (catch Exception e (log/error e \"failed\") nil)\n"
                        "  (catch Object _ :default))"))))))))

(deftest mutable-state-test
  (is (= [{:name 'cache :line 2 :kind :atom}
          {:name 'counter :line 3 :kind :ref}
          {:name '*conn* :line 4 :kind :dynamic}
          {:name 'once :line 5 :kind :volatile}
          {:name "#'f" :line 6 :kind :alter-var-root}]
        (:mutable-state
         (measure (str "(ns n)\n"
                    "(def cache (atom {}))\n"
                    "(def ^:private counter \"doc\" (ref 0))\n"
                    "(def ^:dynamic *conn* nil)\n"
                    "(defonce once (volatile! 1))\n"
                    "(defn g [] (alter-var-root #'f inc))\n"
                    "(def plain {:a 1})\n"
                    "(defn h [] (let [a (atom 0)] @a))\n"))))
    "local atoms and plain values aren't mutable state"))

(deftest columns-test
  (is (= ["Forms" "Functions" "Mean function complexity"
          "Mean nesting depth"]
        (map :label (take 4 (:columns (second metrics/sections))))))
  (is (= "2.5"
        (metrics/column-text (nth (:columns (second metrics/sections)) 2)
          {:mean-function-complexity 2.46})))
  (is (= "patient (1%)"
        (metrics/column-text {:keys [:merge-candidate]}
          {:merge-candidate 0.012 :merge-candidate-subject "patient"}))
    "a value with its subject, as a percentage")
  (is (= "0.33" (metrics/format-value :instability 1/3)))
  (is (= "–" (metrics/format-value :instability nil))))

(deftest averages-test
  (is (= {:forms 15.0
          :functions 2.0
          :mean-function-complexity 4.0
          :mean-function-depth 3.0
          :afferent 1.0
          :efferent nil
          :instability 0.5
          :cohesion nil}
        (select-keys
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
                          {:complexity 3 :depth 2}]}])
          [:forms :functions :mean-function-complexity
           :mean-function-depth :afferent :efferent :instability :cohesion]))
    "the mean of each brick's value, skipping bricks without one")
  (is (= (set (map :key metrics/metrics))
        (set (keys (metrics/averages []))))
    "every metric")
  (is (= {:mutable-state 1.0}
        (select-keys (metrics/averages
                       [{:brick {:type :component} :metrics {:mutable-state 1}}
                        {:brick {:type :base} :metrics {:mutable-state 5}}])
          [:mutable-state]))
    "of the brick types the metric checks")
  (is (= "1.5" (metrics/format-value :forms 1.5)))
  (is (= "2" (metrics/format-value :forms 2.0))))

(deftest outliers-test
  (let [bricks (fn [k & values]
                 (map-indexed (fn [i v]
                                {:brick {:name (str "b" i) :type :component}
                                 :metrics (assoc {:forms 10} k v)})
                   values))]
    (testing "values at least k sample standard deviations past the others"
      (let [o (metrics/outliers (bricks :interop-density 1 1 2 2 1 2 9) 2)]
        (is (= [["b6" :interop-density]] (keys o)))
        (is (= 1.5 (:mean (o ["b6" :interop-density]))))
        (is (< 13.69 (:z (o ["b6" :interop-density])) 13.70))))
    (testing "only in the metric's direction"
      (is (empty? (metrics/outliers (bricks :interop-density 9 9 8 8 9 8 1)
                    2))
        "low interop density is fine")
      (is (= [["b6" :test-ratio]]
            (keys (metrics/outliers (bricks :test-ratio 9 9 8 8 9 8 1) 2)))
        "low test ratio is not"))
    (testing "only ratios, densities, and means"
      (is (empty? (metrics/outliers (bricks :forms 10 10 10 10 10 10 900)
                    2))))
    (testing "no outliers without variation or with too few values"
      (is (empty? (metrics/outliers (bricks :interop-density 5 5 5 5) 2)))
      (is (empty? (metrics/outliers (bricks :interop-density 1 1 100) 0.5))))
    (testing "only the bricks the metric applies to"
      (let [measurements (concat
                           (bricks :untested-interface 0.1 0.2 0.1 0.2)
                           [{:brick {:name "small" :type :component}
                             :metrics {:untested-interface 1.0
                                       :definitions 9}}])
            gated (map #(assoc-in % [:metrics :definitions] 10)
                    measurements)]
        (is (empty? (metrics/outliers (map #(update % :metrics dissoc
                                              :definitions)
                                        (butlast gated))
                      2)))
        (is (empty? (metrics/outliers (concat (butlast gated)
                                        [(last measurements)])
                      2))
          "below the gate, not outlined")
        (is (= {:peers :components}
              (select-keys ((metrics/outliers gated 2)
                            ["small" :untested-interface])
                [:peers])))))))

(deftest section-measurements-test
  (let [measurements [{:brick {:name "c" :type :component}}
                      {:brick {:name "b" :type :base}}]
        section (fn [k] (first (filter #(= k (:key %)) metrics/sections)))]
    (is (= ["c" "b"] (map (comp :name :brick)
                       (metrics/section-measurements (section :dependencies)
                         measurements)))
      "every table shows bases")
    (is (= ["c" "b"] (map (comp :name :brick)
                       (metrics/section-measurements (section :modularity)
                         measurements))))))

(deftest cohesion-average-test
  (is (= 0.8 (:cohesion (metrics/averages
                          [{:brick {:name "c" :type :component}
                            :metrics {:cohesion 0.8}}
                           {:brick {:name "b" :type :base}
                            :metrics {:cohesion 0.1}}])))
    "a base's cohesion doesn't count toward the average"))

(deftest definitions-test
  (is (= [{:name 'labels :line 1 :references #{'metrics/metrics 'into}
           :private? true :throws? false}
          {:name 'f :line 2 :references #{'labels} :private? true
           :throws? false}]
        (:definitions
         (measure "(def ^:private labels (into {} metrics/metrics))\n(defn ^:private f [] labels)"))))
  (is (= [true true false false]
        (map :private?
          (:definitions
           (measure (str "(defn- a [] 1)\n"
                      "(def ^{:private true :doc \"x\"} b 1)\n"
                      "(def ^{:private false} c 1)\n"
                      "(defn ^:dynamic d [] 1)"))))))
  (is (= "f" (-> (measure "(defn ^:private f [] 1)") :functions first :name))
    "metadata isn't part of a function's name"))

(deftest keywords-test
  (is (= #{:id :name :x/qualified}
        (:keywords (measure (str "(ns foo (:require [a :as b]))\n"
                              "(defn f [{:keys [id]}] {:id id :name 1"
                              " :x/qualified 2})"))))
    "data keywords only: not the ns form or syntax like :keys")
  (is (= #{:foo/own :a/aliased :c.d/as-aliased :zz/unknown :p/id}
        (:keywords (measure (str "(ns foo (:require [a :as b]"
                              " [c.d :as-alias d]))\n"
                              "[::own ::b/aliased ::d/as-aliased ::zz/unknown"
                              " #:p{:id 1}]"))))
    (str "auto-resolved keywords resolve in the file's namespace and its"
      " aliases; an unknown alias stays as written")))

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
  (is (= "Complex functions" (metrics/label {:metric :function-complexity})))
  (is (= "Custom" (metrics/label {:metric :forms :label "Custom"})))
  (is (= "mystery" (metrics/label {:metric :mystery}))))
