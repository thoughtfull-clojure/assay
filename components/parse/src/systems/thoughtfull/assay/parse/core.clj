(ns systems.thoughtfull.assay.parse.core
  (:require
   [rewrite-clj.node :as n]
   [rewrite-clj.parser :as p]))

(defn parse-string
  [s]
  (p/parse-string-all s))

(defn code-node?
  [node]
  (not (or (n/whitespace-or-comment? node)
         (= :uneval (n/tag node)))))

(defn code-children
  [node]
  (when (n/inner? node)
    (filter code-node? (n/children node))))

(defn head-symbol
  [node]
  (when (= :list (n/tag node))
    (let [head (first (code-children node))]
      (when (and head (= :token (n/tag head)))
        (let [v (n/sexpr head)]
          (when (symbol? v)
            (name v)))))))
