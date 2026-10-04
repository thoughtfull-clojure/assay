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
  "The code children of node, or nil if node has no children."
  [node]
  (core/code-children node))

(defn top-level-forms
  "The top-level code nodes of a :forms node."
  [forms]
  (core/code-children forms))

(defn head-symbol
  "The unqualified name of the symbol at the head of a list node, or nil."
  [node]
  (core/head-symbol node))
