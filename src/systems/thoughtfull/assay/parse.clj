(ns systems.thoughtfull.assay.parse
  "Read Clojure source into rewrite-clj nodes, preserving everything the
  reader would discard (comments, whitespace, reader macros) so metrics
  can see the code as written."
  (:require
   [rewrite-clj.node :as n]
   [rewrite-clj.parser :as p]))

(defn parse-string
  "Parse a string of Clojure source into a rewrite-clj :forms node."
  [s]
  (p/parse-string-all s))

(defn parse-file
  "Parse a Clojure source file into a rewrite-clj :forms node."
  [f]
  (p/parse-file-all f))

(defn code-node?
  "True if node is code rather than whitespace, a comment, or uneval (#_)."
  [node]
  (not (or (n/whitespace-or-comment? node)
         (= :uneval (n/tag node)))))

(defn top-level-forms
  "The top-level code nodes of a :forms node."
  [forms]
  (filter code-node? (n/children forms)))
