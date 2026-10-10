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

(defn- reader-conditional-tag
  "The ? or ?@ of a reader conditional node, or nil."
  [node]
  (when (= :reader-macro (n/tag node))
    (#{"?" "?@"} (some-> (first (n/children node)) n/string))))

(defn reader-conditional-node?
  [node]
  (boolean (reader-conditional-tag node)))

(declare code-children)

(defn- branches
  "The values of a reader conditional's branches, without their features."
  [node]
  (take-nth 2 (rest (code-children (last (n/children node))))))

(defn code-children
  [node]
  (cond
    (reader-conditional-node? node) (branches node)
    (n/inner? node) (filter code-node? (n/children node))))

(defn- splice
  "nodes with each reader conditional replaced by its branch values, and
  each splicing one (#?@) by the contents of its branch values."
  [nodes]
  (mapcat (fn [node]
            (case (reader-conditional-tag node)
              nil [node]
              "?" (splice (branches node))
              "?@" (splice (mapcat code-children (branches node)))))
    nodes))

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

(defn- refers
  [node]
  (when (some-> node n/tag #{:vector})
    (filter symbol? (map token-value (code-children node)))))

(defn- libspec-options
  "The :as and :as-alias aliases and :refer (and ClojureScript's
  :refer-macros) symbols of a libspec's options, when present."
  [option-nodes]
  (let [options (into {}
                  (for [[k v] (partition 2 option-nodes)
                        :when (keyword? (token-value k))]
                    [(token-value k) v]))
        alias (some-> (options :as) token-value)
        as-alias (some-> (options :as-alias) token-value)
        refer (vec (mapcat refers [(options :refer) (options :refer-macros)]))]
    (cond-> {}
      (symbol? alias) (assoc :as alias)
      (symbol? as-alias) (assoc :as-alias as-alias)
      (seq refer) (assoc :refer refer))))

(defn- lib?
  "True if v names a library: a namespace symbol, or a string naming a
  JavaScript module in ClojureScript."
  [v]
  (or (symbol? v) (string? v)))

(defn- libspec-entries
  "Required namespaces of one :require or :use entry, each a map of :ns,
  :line, and when present :as and :refer. Handles symbols, strings,
  libspec vectors, and prefix lists."
  [node]
  (let [line (:row (meta node))
        children (splice (code-children node))
        [head second-child] children]
    (cond
      (lib? (token-value node))
      [{:ns (token-value node) :line line}]

      (not (#{:vector :list} (n/tag node)))
      []

      (or (nil? second-child) (keyword? (token-value second-child)))
      (if (lib? (token-value head))
        [(merge {:ns (token-value head) :line line}
           (libspec-options (rest children)))]
        [])

      :else
      (let [prefix (token-value head)]
        (for [child (rest children)
              entry (libspec-entries child)
              :when prefix]
          (update entry :ns #(symbol (str prefix "." %))))))))

(defn- unwrap-meta
  "The node that metadata such as ^:no-doc is attached to, or node."
  [node]
  (if (= :meta (some-> node n/tag))
    (recur (last (code-children node)))
    node))

(def ^:private require-clauses
  #{:require :use :require-macros :use-macros})

(defn ns-info
  [forms]
  (when-let [ns-form (first (filter #(= "ns" (head-symbol %))
                              (code-children forms)))]
    {:ns (token-value (unwrap-meta (second (code-children ns-form))))
     :line (:row (meta ns-form))
     :requires (vec
                 (for [clause (splice (code-children ns-form))
                       :when (require-clauses
                               (some-> (first (code-children clause))
                                 token-value))
                       entry (splice (rest (code-children clause)))
                       required (libspec-entries entry)]
                   required))}))
