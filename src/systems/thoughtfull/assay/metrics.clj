(ns systems.thoughtfull.assay.metrics
  "Metric functions. Each takes a rewrite-clj :forms node and returns a
  number."
  (:require
   [rewrite-clj.node :as n]
   [systems.thoughtfull.assay.parse :as parse]))

(defn top-level-form-count
  "Number of top-level forms."
  [forms]
  (count (parse/top-level-forms forms)))

(defn- code-nodes
  [node]
  (tree-seq n/inner? #(filter parse/code-node? (n/children %)) node))

(defn form-count
  "Number of forms at any depth, including atoms such as symbols and
  literals."
  [forms]
  (dec (count (code-nodes forms))))

;; TODO: cyclomatic complexity, max nesting depth, function length,
;; arity counts, etc.

(def metrics
  "Registry of metric name to metric function."
  {:top-level-form-count top-level-form-count
   :form-count form-count})
