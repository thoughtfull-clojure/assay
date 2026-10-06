(ns systems.thoughtfull.assay.metrics.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [rewrite-clj.node :as n]
   [systems.thoughtfull.assay.parse.interface :as parse]))

(def metrics
  [{:key :files
    :section :complexity
    :label "Files"
    :description "Clojure source files under src."}
   {:key :forms
    :section :complexity
    :label "Forms"
    :description "Forms at any depth, including symbols and literals."}
   {:key :functions
    :section :complexity
    :label "Functions"
    :description "defn, defn-, defmacro, and defmethod forms."}
   {:key :mean-function-complexity
    :section :complexity
    :label "Mean function complexity"
    :description "Mean cyclomatic complexity of the brick's functions."
    :format :decimal
    :precision 1}
   {:key :mean-function-depth
    :section :complexity
    :label "Mean nesting depth"
    :description "Mean nesting depth of the brick's functions."
    :format :decimal
    :precision 1}
   {:key :afferent
    :section :dependencies
    :label "Afferent (Ca)"
    :description "Bricks that depend on this brick's interface."
    :components-only true}
   {:key :efferent
    :section :dependencies
    :label "Efferent (Ce)"
    :description "Interfaces this brick depends on."}
   {:key :instability
    :section :dependencies
    :label "Instability"
    :description "Ce / (Ca + Ce): 0 is stable, 1 is unstable."
    :format :decimal
    :components-only true}
   {:key :abstractness
    :section :modularity
    :label "Abstractness"
    :description (str "1 - interface definitions / all definitions: how"
                   " much the interface hides. Bases are 0.")
    :format :decimal
    :components-only true}
   {:key :cohesion
    :section :modularity
    :label "Cohesion"
    :description "Own-namespace references / all workspace references."
    :format :decimal}
   {:key :shared-keywords
    :section :modularity
    :label "Shared keywords"
    :description "Keywords this brick uses that other bricks also use."}
   {:key :libraries
    :section :io
    :label "Libraries"
    :description "Libraries outside the workspace that the brick requires."}
   {:key :shared-libraries
    :section :io
    :label "Shared libraries"
    :description "Of those, libraries that another brick also requires."}
   {:key :interop
    :section :io
    :label "Host interop"
    :description (str "Java interop forms: method calls, field access,"
                   " constructors, and static members.")}
   {:key :interop-density
    :section :io
    :label "Interop density"
    :description "Host interop forms per 100 forms."
    :format :decimal
    :precision 1}
   {:key :mutable-state
    :section :io
    :label "Mutable state"
    :description (str "Top-level atoms, refs, agents, volatiles, dynamic"
                   " vars, and alter-var-root calls.")}
   {:key :error-surface
    :section :errors
    :label "Error surface"
    :description "Interface definitions that can throw, directly or not."
    :components-only true}
   {:key :untyped-errors
    :section :errors
    :label "Untyped errors"
    :description (str "Throws of Java exceptions, or of ex-info without a"
                   " :type key.")}
   {:key :catches
    :section :errors
    :label "Catches"
    :description "catch clauses."}
   {:key :broad-catches
    :section :errors
    :label "Broad catches"
    :description "catch clauses for Exception, Throwable, and the like."}
   {:key :tests
    :section :tests
    :label "Tests"
    :description "deftest forms under test."}
   {:key :assertions-per-test
    :section :tests
    :label "Assertions per test"
    :description "Mean is and are assertions per deftest."
    :format :decimal
    :precision 1}
   {:key :forms-per-test
    :section :tests
    :label "Forms per test"
    :description "Mean forms per deftest."
    :format :decimal
    :precision 1}
   {:key :untested-interface
    :section :tests
    :label "Untested interface"
    :description "Interface definitions no test in the workspace mentions."
    :components-only true}
   {:key :isolation-hazards
    :section :tests
    :label "Isolation hazards"
    :description (str "with-redefs, Thread/sleep, alter-var-root, and"
                   " top-level mutable state in tests.")}
   {:key :test-ratio
    :section :tests
    :label "Test ratio"
    :description "Test forms per source form."
    :format :decimal}])

;; Explanations are legend entries. Code and formulas are in backticks,
;; which reports render as code.

(def ^:private brick-explanations
  {:files "Clojure source files under the brick's `src` directory."
   :forms (str "Every form at any depth: collections, symbols, and"
            " literals. A measure of size that ignores formatting and"
            " comments.")
   :functions "`defn`, `defn-`, `defmacro`, and `defmethod` definitions."
   :mean-function-complexity
   (str "The mean cyclomatic complexity of the brick's functions. A rising"
     " mean means the brick as a whole is getting harder to follow. The"
     " function rules catch the most complex functions.")
   :mean-function-depth
   (str "The mean nesting depth of the brick's functions. See nesting"
     " depth under functions.")
   :afferent
   (str "How many bricks depend on this brick's interface. A high count"
     " means a change here ripples widely, so the interface should change"
     " rarely.")
   :efferent
   (str "How many interfaces this brick depends on. A high count means"
     " this brick is exposed to changes in many others.")
   :instability
   (str "`Ce / (Ca + Ce)`, from `0` (stable: others depend on it and it"
     " depends on little) to `1` (unstable: free to change, since nothing"
     " depends on it). Bricks should depend only on more stable bricks.")
   :abstractness
   (str "`1 - interface definitions / all definitions`, counting `def`,"
     " `defn`, `defmethod`, and the like. A small interface over a large"
     " implementation scores near `1`. A component whose interface is most"
     " of its definitions hides little: at `0.5`, each interface definition"
     " hides only one more. Bases are `0`.")
   :cohesion
   (str "`own references / workspace references`: how much the brick's"
     " code refers to its own namespaces rather than to other bricks."
     " References to libraries don't count. A low value means the brick"
     " is mostly glue between other bricks.")
   :shared-keywords
   (str "Keywords this brick uses that another brick also uses: usually"
     " map keys that both must agree on (connascence of meaning). Renaming"
     " one means changing every brick that shares it. Keywords that are"
     " Clojure syntax, such as `:as` and `:keys`, don't count.")
   :libraries
   (str "Libraries outside the workspace that the brick requires, named by"
     " their namespaces, such as `next.jdbc` or `clojure.java.io`. Clojure's"
     " own pure namespaces, such as `clojure.string`, don't count.")
   :shared-libraries
   (str "The brick's libraries that another brick also requires. A library"
     " wrapped by one brick can be replaced or upgraded in one place; a"
     " library spread across bricks, such as a database driver, means a"
     " missing gateway component.")
   :interop
   (str "Java interop forms: method calls and field access (`.method`,"
     " `.-field`, `..`), constructors (`Foo.`, `new`), and static members"
     " (`Math/abs`, `File/separator`). Interop couples code to the host;"
     " spread across bricks rather than wrapped in a few, it makes the"
     " workspace harder to port, test, and read as Clojure.")
   :interop-density
   (str "Host interop forms per 100 forms, so that large and small bricks"
     " compare fairly. A component that wraps a Java API is dense by"
     " design; interop scattered through domain logic isn't.")
   :mutable-state
   (str "Top-level `atom`, `ref`, `agent`, and `volatile!` definitions,"
     " `^:dynamic` vars, and `alter-var-root` calls: state hidden from the"
     " functions that depend on it, which makes tests interfere with each"
     " other.")
   :error-surface
   (str "Interface definitions that can throw: their body contains `throw`,"
     " or refers to a definition that can, in this brick or another. Each"
     " is a failure every caller must be ready for; the fewer, the simpler"
     " the interface. Bases have no interface.")
   :untyped-errors
   (str "Throws that give callers nothing to tell failures apart by: a Java"
     " exception such as `(Exception. msg)`, or `ex-info` whose data map"
     " has no `:type` key (or `:cognitect.anomalies/category`). Rethrows,"
     " and data that isn't a literal map, don't count.")
   :catches
   (str "`catch` clauses. Catching belongs where a failure can be handled,"
     " usually at the edges, in bases.")
   :broad-catches
   (str "`catch` clauses for `Exception`, `RuntimeException`, `Throwable`,"
     " or `Object`. In a component, a broad catch decides for every caller"
     " what a failure means.")
   :tests "`deftest` forms in the brick's `test` directory."
   :assertions-per-test
   (str "The mean number of `is` and `are` assertions per `deftest`. A test"
     " with many assertions checks many things, so a failure says less"
     " about what broke.")
   :forms-per-test
   (str "The mean size of a `deftest`, in forms. Large tests usually set up"
     " a lot of state or check many behaviors at once.")
   :untested-interface
   (str "Interface definitions that no test anywhere in the workspace"
     " mentions: API with no test at all. Bases have no interface.")
   :isolation-hazards
   (str "Things in tests that let tests affect each other or depend on"
     " timing: `with-redefs`, `Thread/sleep`, `alter-var-root`, and"
     " top-level atoms, refs, agents, volatiles, and dynamic vars.")
   :test-ratio
   (str "Test forms per source form. A crude measure of how much testing a"
     " brick has; compare it with other bricks rather than aim for a"
     " number.")})

(def ^:private metric-index
  (into {} (map (juxt :key identity)) metrics))

(def columns
  (mapv (fn [{:keys [key label description section]}]
          {:label label
           :description description
           :explanation (brick-explanations key)
           :section section
           :keys [key]})
    metrics))

(def sections
  (vec (for [[key label] [[:dependencies "Dependencies"]
                          [:complexity "Complexity"]
                          [:modularity "Modularity"]
                          [:io "I/O and mutability"]
                          [:errors "Error handling"]
                          [:tests "Tests"]]]
         {:key key
          :label label
          :columns (filterv #(= key (:section %)) columns)})))

(defn format-value
  [k v]
  (let [{:keys [format precision] :or {precision 2}} (metric-index k)]
    (cond
      (nil? v) "–"
      (= :decimal format) (clojure.core/format (str "%." precision "f")
                            (double v))
      (== v (Math/rint v)) (str (long v))
      :else (clojure.core/format "%.1f" (double v)))))

(defn column-text
  [{:keys [keys]} metrics]
  (str/join " / " (map #(format-value % (get metrics %)) keys)))

(defn- mean-of
  [xs]
  (when (seq xs)
    (/ (reduce + xs) (double (count xs)))))

(defn- peers
  "The measurements a metric compares: components only, when bases' values
  follow from Polylith's structure (bases have no interface and no
  dependents)."
  [{:keys [components-only]} measurements]
  (if components-only
    (filter #(= :component (get-in % [:brick :type])) measurements)
    measurements))

(defn averages
  [measurements]
  (into {}
    (for [{:keys [key] :as metric} metrics]
      [key (mean-of (keep #(get-in % [:metrics key])
                      (peers metric measurements)))])))

(def outlier-std-devs
  2)

(defn outliers
  [measurements k]
  (into {}
    (for [{:keys [key components-only] :as metric} metrics
          :let [values (keep #(when-some [v (get-in % [:metrics key])]
                                [(get-in % [:brick :name]) v])
                         (peers metric measurements))]
          :when (<= 3 (count values))
          :let [xs (map second values)
                m (mean-of xs)
                sd (Math/sqrt (mean-of (map #(Math/pow (- % m) 2) xs)))]
          :when (pos? sd)
          [brick-name v] values
          :let [z (/ (- v m) sd)]
          :when (<= k (abs z))]
      [[brick-name key] {:z z :mean m :std-dev sd
                         :peers (if components-only :components :bricks)}])))

(def ^:private function-metrics
  [{:key :complexity :label "Complexity"}
   {:key :depth :label "Nesting depth"}
   {:key :forms :label "Forms"}
   {:key :params :label "Parameters"}])

;; Forms and nesting

(defn- form-count
  [forms]
  (dec (count (tree-seq n/inner? parse/code-children forms))))

;; Nesting depth counts nested collections, except that:
;;
;; - In a form with a binding vector (let, loop, for, and so on), only the
;;   body nests inside the form. Each binding value starts again at depth 1,
;;   and the binding vector and binding names add nothing.
;; - In fn, defn, and similar forms, parameter vectors add nothing.

(def ^:private binding-heads
  #{"let" "let*" "loop" "loop*" "binding" "with-open" "with-redefs"
    "with-local-vars" "if-let" "if-some" "when-let" "when-some" "when-first"
    "dotimes" "for" "doseq"})

(def ^:private params-heads
  #{"fn" "fn*" "defn" "defn-" "defmacro" "defmethod"})

(def ^:private shallowest
  {:depth 0 :node nil})

(defn- deeper
  "The deeper of two results, preferring the first on a tie."
  [a b]
  (if (> (:depth b) (:depth a)) b a))

(declare deepest-at)

(defn- deepest-of
  "The deepest result among nodes, each at depth d."
  [nodes d]
  (reduce deeper shallowest (map #(deepest-at % d) nodes)))

(defn- vector-node?
  [node]
  (= :vector (n/tag node)))

(defn- binding-values
  "The value nodes of a binding vector. A :let modifier (in for and doseq)
  contributes the values of its own binding vector."
  [bindings]
  (mapcat (fn [[k v]]
            (cond
              (nil? v) []
              (and (= :token (n/tag k)) (= :let (n/sexpr k))) (binding-values v)
              :else [v]))
    (partition-all 2 (parse/code-children bindings))))

(defn- binding-form-deepest
  "A form with a binding vector, at depth d. values-deepest returns the
  deepest result among the binding values, which start again at depth 1."
  [node d [bindings & body] values-deepest]
  (reduce deeper {:depth d :node node}
    [(deepest-of body (inc d))
     (values-deepest bindings)]))

(defn- arity-deepest
  "An arity list of a multi-arity fn, at depth d, without its parameters."
  [node d]
  (deeper {:depth d :node node}
    (deepest-of (rest (parse/code-children node)) (inc d))))

(defn- params-form-deepest
  "A fn-like form at depth d. Only the body counts: not the name, docstring,
  attribute map, defmethod dispatch value, or parameters. Each arity of a
  multi-arity form nests, without its parameters."
  [node d head args]
  (let [args (if (= "defmethod" head) (drop 2 args) args)
        [_ [params & body]] (split-with (complement vector-node?) args)
        self {:depth d :node node}]
    (if params
      (deeper self (deepest-of body (inc d)))
      (reduce deeper self
        (for [arg args
              :when (some-> (first (parse/code-children arg)) vector-node?)]
          (arity-deepest arg (inc d)))))))

(defn- letfn-deepest
  "The deepest of letfn's functions, each like a fn at depth 1."
  [fns]
  (reduce deeper shallowest
    (for [f (parse/code-children fns)]
      (params-form-deepest f 1 "fn" (rest (parse/code-children f))))))

(defn- deepest-at
  "The deepest collection in node's tree, when node is at depth d, as a map
  of :depth and :node. Tokens have no depth of their own."
  [node d]
  (let [[_ & args :as children] (parse/code-children node)
        head (parse/head-symbol node)]
    (cond
      (not (n/inner? node)) shallowest
      (and (binding-heads head) (some-> (first args) vector-node?))
      (binding-form-deepest node d args
        #(deepest-of (binding-values %) 1))
      (and (= "letfn" head) (some-> (first args) vector-node?))
      (binding-form-deepest node d args letfn-deepest)
      (params-heads head) (params-form-deepest node d head args)
      :else (deeper {:depth d :node node}
              (deepest-of children (inc d))))))

;; Cyclomatic complexity
;;
;; A function's complexity is 1 plus its decision points, counted over its
;; whole body including nested fns:
;;
;; - if, if-not, if-let, if-some, when, when-not, when-let, when-some,
;;   when-first, while: 1
;; - case: 1, however many clauses. Its tests are constants dispatched in
;;   one step, so it reads like a lookup table; only its bodies add more.
;; - cond: 1 per clause, less 1 for a final :else (or other keyword or true)
;; - condp, cond->, cond->>: 1 per clause
;; - and, or: 1 per argument after the first
;; - catch: 1
;; - :when and :while in for and doseq: 1 each
;; - fn, defn, and similar: 1 per arity after the first

(def ^:private function-heads
  #{"defn" "defn-" "defmacro" "defmethod"})

(defn- token-value
  [node]
  (when (= :token (n/tag node))
    (n/sexpr node)))

(defn- else?
  [node]
  (let [v (token-value node)]
    (or (keyword? v) (true? v))))

(defn- cond-decisions
  [args]
  (let [clauses (quot (count args) 2)]
    (if (and (pos? clauses) (else? (nth args (* 2 (dec clauses)))))
      (dec clauses)
      clauses)))

(defn- binding-modifiers
  "Count of :when and :while modifiers in the bindings of for or doseq."
  [args]
  (count (filter (comp #{:when :while} token-value)
           (parse/code-children (first args)))))

(defn- extra-arities
  "Arities of a fn-like form after the first. A single arity has its
  parameter vector directly in the form; multiple arities are each a list."
  [args]
  (if (some #(= :vector (n/tag %)) args)
    0
    (max 0 (dec (count (filter #(= :list (n/tag %)) args))))))

(def ^:private decision-counters
  "Form head to a function of the form's arguments returning its decision
  points."
  (merge
    (zipmap ["if" "if-not" "if-let" "if-some" "when" "when-not" "when-let"
             "when-some" "when-first" "while" "catch" "case"]
      (repeat (constantly 1)))
    (zipmap ["cond->" "cond->>"]
      (repeat #(quot (count (rest %)) 2)))
    (zipmap ["and" "or"]
      (repeat #(max 0 (dec (count %)))))
    (zipmap ["for" "doseq"]
      (repeat binding-modifiers))
    (zipmap ["fn" "fn*" "defn" "defn-" "defmacro"]
      (repeat extra-arities))
    {"cond" cond-decisions
     "condp" #(quot (count (drop 2 %)) 2)}))

(defn- decisions
  [node]
  (if-let [counter (decision-counters (parse/head-symbol node))]
    (counter (rest (parse/code-children node)))
    0))

(defn- complexity
  [node]
  (inc (reduce + (map decisions
                   (tree-seq n/inner? parse/code-children node)))))

(defn- unwrap-meta
  "The node that metadata such as ^:private is attached to, or node."
  [node]
  (if (= :meta (some-> node n/tag))
    (last (parse/code-children node))
    node))

(defn- function-name
  [node]
  (let [[_ name-node dispatch] (parse/code-children node)
        name-node (unwrap-meta name-node)]
    (cond-> (n/string name-node)
      (= "defmethod" (parse/head-symbol node))
      (str " " (n/string dispatch)))))

(defn- param-vectors
  "The parameter vectors of a fn-like form, one per arity."
  [node]
  (let [[_ & args] (parse/code-children node)
        args (if (= "defmethod" (parse/head-symbol node)) (drop 2 args) args)]
    (if-let [params (first (filter vector-node? args))]
      [params]
      (keep #(let [first-child (first (parse/code-children %))]
               (when (some-> first-child vector-node?) first-child))
        args))))

(defn- positional-params
  [params]
  (count (take-while #(not= '& (token-value %)) (parse/code-children params))))

(defn- function
  [file ns node]
  (let [deepest (deepest-at node 1)]
    {:name (function-name node)
     :ns ns
     :file file
     :line (:row (meta node))
     :complexity (complexity node)
     :depth (:depth deepest)
     :depth-line (:row (meta (:node deepest)))
     :forms (count (tree-seq n/inner? parse/code-children node))
     :params (apply max 0 (map positional-params (param-vectors node)))}))

;; Keywords and fragments, for connascence of meaning and of algorithm

(def ^:private syntax-keywords
  "Keywords that are Clojure syntax rather than data."
  #{:as :refer :refer-clojure :require :use :import :exclude :rename :only
    :all :keys :strs :syms :or :let :when :while :else :default :pre :post
    :private :dynamic :const :doc :arglists :tag :added :deprecated
    :gen-class :load :reload :verbose :as-alias})

(defn- keywords
  "Data keywords in forms other than the ns form."
  [top-level]
  (into (sorted-set)
    (comp (remove #(= "ns" (parse/head-symbol %)))
      (mapcat #(tree-seq n/inner? parse/code-children %))
      (keep token-value)
      (filter keyword?)
      (remove syntax-keywords))
    top-level))

(def fragment-min-forms
  "The fewest forms in a fragment kept for duplicate detection."
  20)

(defn- canonical
  "Form count and a canonical string of node, ignoring whitespace and
  comments, with the fragments of at least fragment-min-forms forms in its
  tree, each {:hash :forms :line :end-line}."
  [node]
  (if-let [children (when (n/inner? node) (parse/code-children node))]
    (let [results (map canonical children)
          forms (inc (reduce + (map :forms results)))
          text (str "(" (name (n/tag node)) " "
                 (str/join " " (map :text results)) ")")
          {:keys [row end-row]} (meta node)]
      {:forms forms
       :text text
       :fragments (cond-> (vec (mapcat :fragments results))
                    (>= forms fragment-min-forms)
                    (conj {:hash (hash text) :forms forms
                           :line row :end-line end-row}))})
    {:forms 1
     :text (n/string node)
     :fragments []}))

(defn- fragments
  [top-level]
  (vec (mapcat (comp :fragments canonical)
         (remove #(= "ns" (parse/head-symbol %)) top-level))))

(def ^:private definition-heads
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defonce"})

(defn- definition
  "A top-level definition's :name (a symbol), :line, :references, the
  symbols in its body, and :throws?, true if its body throws. A defmethod is
  named for its multimethod."
  [node]
  (let [[_ name-node & body] (parse/code-children node)
        definition-name (token-value (unwrap-meta name-node))
        nodes (mapcat #(tree-seq n/inner? parse/code-children %) body)]
    (when (symbol? definition-name)
      {:name definition-name
       :line (:row (meta node))
       :references (into #{} (comp (keep token-value) (filter symbol?)) nodes)
       :throws? (boolean (some #(= "throw" (parse/head-symbol %)) nodes))})))

;; Error handling

(def ^:private broad-exceptions
  #{"Exception" "java.lang.Exception" "RuntimeException"
    "java.lang.RuntimeException" "Throwable" "java.lang.Throwable"
    "Object" "java.lang.Object"})

(defn- typed-data?
  "True if an ex-info data map has a :type key, in any namespace, or a
  :cognitect.anomalies/category key."
  [map-node]
  (some #(let [k (token-value %)]
           (and (keyword? k)
             (or (= "type" (name k)) (= :cognitect.anomalies/category k))))
    (take-nth 2 (parse/code-children map-node))))

(defn- thrown-kind
  "What a throw form throws: :typed or :untyped ex-info (by its literal data
  map), :unknown ex-info data, a :java exception constructed in place, or
  a :rethrow of something else."
  [throw-node]
  (let [arg (second (parse/code-children throw-node))
        head (some-> arg parse/head-symbol)]
    (cond
      (= "ex-info" head)
      (let [data (nth (parse/code-children arg) 2 nil)]
        (cond
          (not= :map (some-> data n/tag)) :unknown
          (typed-data? data) :typed
          :else :untyped))

      (or (= "new" head) (some-> head (str/ends-with? "."))) :java
      :else :rethrow)))

(defn- error-handling
  "Every throw, as {:line :kind}, and every catch clause, as {:line :class
  :broad?}, in top-level forms."
  [top-level]
  (let [nodes (mapcat #(tree-seq n/inner? parse/code-children %) top-level)]
    {:throws (vec (for [node nodes
                        :when (= "throw" (parse/head-symbol node))]
                    {:line (:row (meta node)) :kind (thrown-kind node)}))
     :catches (vec (for [node nodes
                         :when (= "catch" (parse/head-symbol node))
                         :let [class (some-> (second (parse/code-children node))
                                       n/string)]]
                     {:line (:row (meta node))
                      :class class
                      :broad? (contains? broad-exceptions class)}))}))

;; Host interop

(defn- class-name?
  "True if s names a Java class, such as String or java.io.File: its last
  dot-separated segment starts with an uppercase letter."
  [s]
  (boolean (some-> s (str/split #"\.") last first Character/isUpperCase)))

(defn- interop?
  "True if node is a symbol for Java interop: a method or field (.foo,
  .-foo, ..), a constructor (Foo.), new, or a static member (Math/abs)."
  [node]
  (let [sym (token-value node)]
    (and (symbol? sym)
      (let [nm (name sym)
            ns (namespace sym)]
        (or (class-name? ns)
          (and (nil? ns)
            (or (= "new" nm)
              (and (str/starts-with? nm ".") (< 1 (count nm)))
              (and (str/ends-with? nm ".") (< 1 (count nm))
                (class-name? (subs nm 0 (dec (count nm))))))))))))

(defn- interop-count
  [top-level]
  (count (filter interop?
           (mapcat #(tree-seq n/inner? parse/code-children %) top-level))))

;; Mutable state

(def ^:private stateful-constructors
  {"atom" :atom "ref" :ref "agent" :agent "volatile!" :volatile})

(defn- dynamic?
  "True if a definition's name node carries ^:dynamic or {:dynamic true}."
  [name-node]
  (some #(= :dynamic (token-value %))
    (tree-seq n/inner? parse/code-children name-node)))

(defn- definition-symbol
  [name-node]
  (loop [node name-node]
    (if (= :meta (some-> node n/tag))
      (recur (unwrap-meta node))
      (token-value node))))

(defn- mutable-state
  "Top-level definitions of mutable state, and alter-var-root calls
  anywhere, each a map of :name, :line, and :kind (:atom, :ref, :agent,
  :volatile, :dynamic, or :alter-var-root)."
  [top-level]
  (concat
    (for [node top-level
          :when (#{"def" "defonce"} (parse/head-symbol node))
          :let [[_ name-node & more] (parse/code-children node)
                sym (definition-symbol name-node)
                kind (if (dynamic? name-node)
                       :dynamic
                       (stateful-constructors
                         (some-> (last more) parse/head-symbol)))]
          :when (and (symbol? sym) kind)]
      {:name sym :line (:row (meta node)) :kind kind})
    (for [form top-level
          node (tree-seq n/inner? parse/code-children form)
          :when (= "alter-var-root" (parse/head-symbol node))]
      {:name (some-> (second (parse/code-children node)) n/string)
       :line (:row (meta node))
       :kind :alter-var-root})))

(defn measure-source
  [file source]
  (let [forms (parse/parse-string source)
        info (parse/ns-info forms)
        top-level (parse/top-level-forms forms)]
    (merge
      (select-keys info [:ns :requires])
      {:file file
       :forms (form-count forms)
       :top-level-forms (count top-level)
       :functions (->> top-level
                    (filter #(contains? function-heads
                               (parse/head-symbol %)))
                    (mapv #(function file (:ns info) %)))
       :definitions (->> top-level
                      (filter #(contains? definition-heads
                                 (parse/head-symbol %)))
                      (keep definition)
                      vec)
       :keywords (keywords top-level)
       :fragments (fragments top-level)
       :mutable-state (vec (mutable-state top-level))
       :interop (interop-count top-level)}
      (error-handling top-level))))

(defn- mean
  [xs]
  (when (seq xs)
    (/ (reduce + xs) (double (count xs)))))

;; Tests

(def ^:private hazard-heads
  #{"with-redefs" "with-redefs-fn" "alter-var-root"})

(defn- hazards
  "Things in tests that let them affect each other or depend on timing,
  each a map of :line and :kind."
  [top-level]
  (concat
    (for [form top-level
          node (tree-seq n/inner? parse/code-children form)
          :let [head (first (parse/code-children node))
                sym (when (= :list (n/tag node)) (some-> head token-value))]
          :when (symbol? sym)
          :let [kind (cond
                       (hazard-heads (name sym)) (keyword (name sym))
                       (= "Thread/sleep" (str sym)) :sleep)]
          :when kind]
      {:line (:row (meta node)) :kind kind})
    (for [{:keys [line kind]} (mutable-state top-level)
          :when (not= :alter-var-root kind)]
      {:line line :kind kind})))

(defn measure-test-source
  [file source]
  (let [forms (parse/parse-string source)
        info (parse/ns-info forms)
        top-level (parse/top-level-forms forms)
        nodes (mapcat #(tree-seq n/inner? parse/code-children %) top-level)]
    (merge
      (select-keys info [:ns :requires])
      {:file file
       :forms (form-count forms)
       :tests (vec (for [node top-level
                         :when (= "deftest" (parse/head-symbol node))
                         :let [body (tree-seq n/inner? parse/code-children
                                      node)]]
                     {:name (some-> (second (parse/code-children node))
                              unwrap-meta token-value)
                      :line (:row (meta node))
                      :forms (count body)
                      :assertions (count (filter #(#{"is" "are"}
                                                   (parse/head-symbol %))
                                           body))}))
       :hazards (vec (hazards top-level))
       :references (into #{} (comp (keep token-value) (filter symbol?))
                     nodes)})))

(defn- test-metrics
  [files tests]
  (let [deftests (mapcat :tests tests)
        source-forms (reduce + (map :forms files))]
    {:tests (count deftests)
     :assertions-per-test (mean (map :assertions deftests))
     :forms-per-test (mean (map :forms deftests))
     :isolation-hazards (count (mapcat :hazards tests))
     :test-ratio (when (pos? source-forms)
                   (/ (reduce + (map :forms tests)) (double source-forms)))}))

(defn measure-brick
  [root brick]
  (let [files (mapv #(measure-source % (slurp (io/file root %)))
                (:files brick))
        tests (mapv #(measure-test-source % (slurp (io/file root %)))
                (:test-files brick))
        functions (vec (mapcat :functions files))
        forms (reduce + (map :forms files))
        interop (reduce + (map :interop files))]
    {:brick brick
     :metrics (merge
                {:files (count files)
                 :forms forms
                 :functions (count functions)
                 :mean-function-complexity (mean (map :complexity functions))
                 :mean-function-depth (mean (map :depth functions))
                 :mutable-state (count (mapcat :mutable-state files))
                 :untyped-errors (count (filter (comp #{:untyped :java} :kind)
                                          (mapcat :throws files)))
                 :catches (count (mapcat :catches files))
                 :broad-catches (count (filter :broad? (mapcat :catches files)))
                 :interop interop
                 :interop-density (when (pos? forms)
                                    (* 100 (/ interop (double forms))))}
                (test-metrics files tests))
     :functions functions
     :sources (mapv #(select-keys % [:file :ns :requires :forms :definitions
                                     :keywords :fragments :mutable-state
                                     :throws :catches])
                files)
     :tests (mapv #(select-keys % [:file :ns :requires :forms :tests :hazards
                                   :references])
              tests)}))

(def ^:private labels
  {:brick (into {} (map (juxt :key :label)) metrics)
   :function (into {} (map (juxt :key :label)) function-metrics)})

(defn label
  [{:keys [scope metric label]}]
  (or label (get-in labels [(or scope :brick) metric]) (name metric)))

(defn function-id
  [{:keys [ns name]}]
  (if ns (str ns "/" name) name))
