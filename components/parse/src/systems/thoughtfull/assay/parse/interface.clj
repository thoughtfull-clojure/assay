(ns systems.thoughtfull.assay.parse.interface
  "Read Clojure source into rewrite-clj nodes, preserving everything the
  reader would discard (comments, whitespace, reader macros) so metrics
  can see the code as written."
  (:require
   [systems.thoughtfull.assay.parse.core :as core]))

(defn parse-string
  "Parse a string of Clojure source into a rewrite-clj :forms node. Nodes
  carry :row and :col metadata."
  [s]
  (core/parse-string s))

(defn code-node?
  "True if node is code rather than whitespace, a comment, or uneval (#_)."
  [node]
  (core/code-node? node))

(defn code-children
  "The code children of node, or nil if node has no children. A reader
  conditional's children are its branch values, for every platform, so
  #?(:clj a :cljs b) has children a and b."
  [node]
  (core/code-children node))

(defn reader-conditional-node?
  "True if node is a reader conditional, #? or #?@."
  [node]
  (core/reader-conditional-node? node))

(defn top-level-forms
  "The top-level code nodes of a :forms node."
  [forms]
  (core/code-children forms))

(defn head-symbol
  "The unqualified name of the symbol at the head of a list node, or nil."
  [node]
  (core/head-symbol node))

(defn ns-info
  "The ns form of a :forms node as a map of :ns (a symbol), :line, and
  :requires, a vector of {:ns lib :line n} for each library in its
  :require, :use, :require-macros, and :use-macros clauses, with :as and
  :as-alias (alias symbols) and :refer (a vector of symbols, including
  :refer-macros) when the libspec has them. A lib is a namespace symbol,
  or a string naming a JavaScript module in ClojureScript. Clauses and
  libspecs in reader conditionals count for every platform. Returns nil
  if there is no ns form."
  [forms]
  (core/ns-info forms))
