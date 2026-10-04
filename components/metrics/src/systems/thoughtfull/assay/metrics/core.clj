(ns systems.thoughtfull.assay.metrics.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [rewrite-clj.node :as n]
   [systems.thoughtfull.assay.parse.interface :as parse]))

(def metrics
  [{:key :files
    :label "Files"
    :description "Clojure source files under src."}
   {:key :lines
    :label "Lines"
    :description "Lines of code, excluding blank and comment-only lines."}
   {:key :top-level-forms
    :label "Top-level forms"
    :description "Forms at the top level of each file."}
   {:key :forms
    :label "Forms"
    :description "Forms at any depth, including symbols and literals."}
   {:key :functions
    :label "Functions"
    :description "defn, defn-, defmacro, and defmethod forms."}
   {:key :cyclomatic-complexity
    :label "Cyclomatic complexity"
    :description "Sum of the cyclomatic complexity of every function."}
   {:key :max-function-complexity
    :label "Max function complexity"
    :description "Cyclomatic complexity of the most complex function."}
   {:key :max-nesting-depth
    :label "Max nesting depth"
    :description "Deepest nesting of collections in any top-level form."}])

;; Lines

(defn- code-line?
  [line]
  (let [line (str/trim line)]
    (not (or (str/blank? line) (str/starts-with? line ";")))))

(defn- line-count
  [source]
  (count (filter code-line? (str/split-lines source))))

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

(defn- function
  [file node]
  {:name (function-name node)
   :file file
   :line (:row (meta node))
   :complexity (complexity node)})

(defn measure-source
  [file source]
  (let [forms (parse/parse-string source)
        top-level (parse/top-level-forms forms)
        deepest (reduce deeper shallowest
                  (for [form top-level]
                    (assoc (deepest-at form 1) :form form)))]
    {:lines (line-count source)
     :forms (form-count forms)
     :top-level-forms (count top-level)
     :max-nesting-depth (:depth deepest)
     :max-nesting-location (when-let [node (:node deepest)]
                             (cond-> {:file file
                                      :line (:row (meta node))}
                               (function-heads (parse/head-symbol
                                                 (:form deepest)))
                               (assoc :name (function-name (:form deepest)))))
     :functions (->> top-level
                  (filter #(contains? function-heads (parse/head-symbol %)))
                  (mapv #(function file %)))}))

(defn- max-by
  [k xs]
  (when (seq xs)
    (apply max-key k xs)))

(defn measure-brick
  [root brick]
  (let [files (mapv #(measure-source % (slurp (io/file root %)))
                (:files brick))
        functions (vec (mapcat :functions files))
        worst-fn (max-by :complexity functions)
        deepest (max-by :max-nesting-depth files)]
    {:brick brick
     :metrics {:files (count files)
               :lines (reduce + (map :lines files))
               :top-level-forms (reduce + (map :top-level-forms files))
               :forms (reduce + (map :forms files))
               :functions (count functions)
               :cyclomatic-complexity (reduce + (map :complexity functions))
               :max-function-complexity (or (:complexity worst-fn) 0)
               :max-nesting-depth (or (:max-nesting-depth deepest) 0)}
     :locations (cond-> {}
                  worst-fn
                  (assoc :max-function-complexity
                    (select-keys worst-fn [:file :line :name]))
                  deepest
                  (assoc :max-nesting-depth (:max-nesting-location deepest)))
     :functions functions}))
