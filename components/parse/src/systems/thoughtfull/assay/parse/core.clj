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

(defn- token-value
  [node]
  (when (= :token (n/tag node))
    (n/sexpr node)))

(defn- libspec-entries
  "Required namespaces of one :require or :use entry, each a map of :ns and
  :line. Handles symbols, libspec vectors, and prefix lists."
  [node]
  (let [line (:row (meta node))
        children (code-children node)
        [head second-child] children]
    (cond
      (symbol? (token-value node))
      [{:ns (token-value node) :line line}]

      (not (#{:vector :list} (n/tag node)))
      []

      (or (nil? second-child) (keyword? (token-value second-child)))
      (if (symbol? (token-value head))
        [{:ns (token-value head) :line line}]
        [])

      :else
      (let [prefix (token-value head)]
        (for [child (rest children)
              {:keys [ns line]} (libspec-entries child)
              :when (and prefix ns)]
          {:ns (symbol (str prefix "." ns)) :line line})))))

(defn ns-info
  [forms]
  (when-let [ns-form (first (filter #(= "ns" (head-symbol %))
                              (code-children forms)))]
    {:ns (token-value (second (code-children ns-form)))
     :line (:row (meta ns-form))
     :requires (vec
                 (for [clause (code-children ns-form)
                       :when (#{:require :use}
                              (some-> (first (code-children clause))
                                token-value))
                       entry (rest (code-children clause))
                       required (libspec-entries entry)]
                   required))}))
