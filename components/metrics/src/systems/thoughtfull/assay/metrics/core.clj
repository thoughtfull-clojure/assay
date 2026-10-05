(ns systems.thoughtfull.assay.metrics.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [rewrite-clj.node :as n]
   [systems.thoughtfull.assay.parse.interface :as parse]))

(def metrics
  [{:key :files
    :label "Files"
    :description "Clojure source files under src."
    :total :sum}
   {:key :forms
    :label "Forms"
    :description "Forms at any depth, including symbols and literals."
    :total :sum}
   {:key :functions
    :label "Functions"
    :description "defn, defn-, defmacro, and defmethod forms."
    :total :sum}
   {:key :mean-function-complexity
    :label "Mean function complexity"
    :description "Mean cyclomatic complexity of the brick's functions."
    :format :decimal
    :precision 1
    :total :function-mean}
   {:key :max-function-complexity
    :label "Max function complexity"
    :description "Cyclomatic complexity of the most complex function."
    :total :max}
   {:key :max-nesting-depth
    :label "Max nesting depth"
    :description "Deepest nesting in any top-level form."
    :total :max}
   {:key :afferent
    :label "Afferent (Ca)"
    :description "Bricks that depend on this brick's interface."}
   {:key :efferent
    :label "Efferent (Ce)"
    :description "Interfaces this brick depends on."}
   {:key :instability
    :label "Instability"
    :description "Ce / (Ca + Ce): 0 is stable, 1 is unstable."
    :format :decimal
    :total :mean}
   {:key :abstractness
    :label "Abstractness"
    :description (str "1 - interface forms / all forms: how much the"
                   " interface hides. Bases are 0.")
    :format :decimal
    :total :mean}])

;; Explanations are legend entries. Code and formulas are in backticks,
;; which reports render as code.

(def ^:private brick-explanations
  {:files "Clojure source files under the brick's `src` directory."
   :forms (str "Every form at any depth: collections, symbols, and"
            " literals. A measure of size that ignores formatting and"
            " comments.")
   :functions "`defn`, `defn-`, `defmacro`, and `defmethod` definitions."
   :function-complexity
   (str "The mean and maximum cyclomatic complexity of the brick's"
     " functions. A rising mean means the brick as a whole is getting"
     " harder to follow; the maximum points at the function to simplify"
     " first.")
   :max-nesting-depth
   (str "The deepest nesting in any top-level form. See nesting depth"
     " under functions.")
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
   (str "`1 - interface forms / all forms`. A small interface over a large"
     " implementation scores near `1`. A component whose interface is most"
     " of its code hides little. Bases are `0`.")})

(def ^:private function-explanations
  {:complexity
   (str "Cyclomatic complexity: `1` plus each decision point (`if`, `when`,"
     " each `cond` or `case` clause, each extra argument to `and` or `or`,"
     " each `catch`, each extra arity). Roughly the number of paths through"
     " the function to understand and test.")
   :depth
   (str "The deepest nesting of collections in the body. Binding values in"
     " `let`, `loop`, `for`, and similar forms start again at `1`, and"
     " parameters, destructuring, and docstrings don't count. Deep nesting"
     " usually means a function that should be split.")
   :forms
   (str "Every form in the function: its size. Long functions are harder"
     " to name, test, and reuse.")
   :params
   (str "Positional parameters of the widest arity, not counting `& more`."
     " Callers must pass them in order, which couples them to the"
     " signature; consider a map for many parameters.")})

(def ^:private metric-index
  (into {} (map (juxt :key identity)) metrics))

(def columns
  (let [combined #{:mean-function-complexity :max-function-complexity}]
    (vec
      (mapcat (fn [{:keys [key label description]}]
                (cond
                  (= :mean-function-complexity key)
                  [{:label "Function complexity"
                    :description (str "Mean / max cyclomatic complexity of"
                                   " the brick's functions.")
                    :explanation (brick-explanations :function-complexity)
                    :keys [:mean-function-complexity
                           :max-function-complexity]}]
                  (combined key) []
                  :else [{:label label
                          :description description
                          :explanation (brick-explanations key)
                          :keys [key]}]))
        metrics))))

(defn format-value
  [k v]
  (let [{:keys [format precision] :or {precision 2}} (metric-index k)]
    (cond
      (nil? v) "–"
      (= :decimal format) (clojure.core/format (str "%." precision "f")
                            (double v))
      :else (str v))))

(defn column-text
  [{:keys [keys]} metrics]
  (str/join " / " (map #(format-value % (get metrics %)) keys)))

(defn- mean-of
  [xs]
  (when (seq xs)
    (/ (reduce + xs) (double (count xs)))))

(defn totals
  [measurements]
  (into {}
    (for [{:keys [key total]} metrics
          :when total
          :let [values (keep #(get-in % [:metrics key]) measurements)]]
      [key (case total
             :sum (reduce + 0 values)
             :max (when (seq values) (apply max values))
             :mean (mean-of values)
             :function-mean (mean-of (map :complexity
                                       (mapcat :functions measurements))))])))

(def function-metrics
  (mapv #(assoc % :explanation (function-explanations (:key %)))
    [{:key :complexity
      :label "Complexity"
      :description "Cyclomatic complexity."}
     {:key :depth
      :label "Nesting depth"
      :description "Deepest nesting in the function body."}
     {:key :forms
      :label "Forms"
      :description "Forms in the function, including symbols and literals."}
     {:key :params
      :label "Parameters"
      :description "Positional parameters of the widest arity."}]))

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
;; - cond: 1 per clause, less 1 for a final :else (or other keyword or true)
;; - condp, case, cond->, cond->>: 1 per clause
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
             "when-some" "when-first" "while" "catch"]
      (repeat (constantly 1)))
    (zipmap ["case" "cond->" "cond->>"]
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

(defn- function-name
  [node]
  (let [[_ name-node dispatch] (parse/code-children node)]
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
  [file node]
  (let [deepest (deepest-at node 1)]
    {:name (function-name node)
     :file file
     :line (:row (meta node))
     :complexity (complexity node)
     :depth (:depth deepest)
     :depth-line (:row (meta (:node deepest)))
     :forms (count (tree-seq n/inner? parse/code-children node))
     :params (apply max 0 (map positional-params (param-vectors node)))}))

(defn measure-source
  [file source]
  (let [forms (parse/parse-string source)
        top-level (parse/top-level-forms forms)
        deepest (reduce deeper shallowest
                  (for [form top-level]
                    (assoc (deepest-at form 1) :form form)))]
    (merge
      (select-keys (parse/ns-info forms) [:ns :requires])
      {:file file
       :forms (form-count forms)
       :top-level-forms (count top-level)
       :max-nesting-depth (:depth deepest)
       :max-nesting-location (when-let [node (:node deepest)]
                               (cond-> {:file file
                                        :line (:row (meta node))}
                                 (function-heads (parse/head-symbol
                                                   (:form deepest)))
                                 (assoc :name
                                   (function-name (:form deepest)))))
       :functions (->> top-level
                    (filter #(contains? function-heads
                               (parse/head-symbol %)))
                    (mapv #(function file %)))})))

(defn- max-by
  [k xs]
  (when (seq xs)
    (apply max-key k xs)))

(defn- mean
  [xs]
  (when (seq xs)
    (/ (reduce + xs) (double (count xs)))))

(defn measure-brick
  [root brick]
  (let [files (mapv #(measure-source % (slurp (io/file root %)))
                (:files brick))
        functions (vec (mapcat :functions files))
        worst-fn (max-by :complexity functions)
        deepest (max-by :max-nesting-depth files)]
    {:brick brick
     :metrics {:files (count files)
               :forms (reduce + (map :forms files))
               :functions (count functions)
               :mean-function-complexity (mean (map :complexity functions))
               :max-function-complexity (or (:complexity worst-fn) 0)
               :max-nesting-depth (or (:max-nesting-depth deepest) 0)}
     :locations (cond-> {}
                  worst-fn
                  (assoc :max-function-complexity
                    (select-keys worst-fn [:file :line :name]))
                  deepest
                  (assoc :max-nesting-depth (:max-nesting-location deepest)))
     :functions functions
     :sources (mapv #(select-keys % [:file :ns :requires :forms]) files)}))
