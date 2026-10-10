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

(defn- libspec-options
  "The :as and :as-alias aliases and :refer symbols of a libspec's options,
  when present."
  [option-nodes]
  (let [options (into {}
                  (for [[k v] (partition 2 option-nodes)
                        :when (keyword? (token-value k))]
                    [(token-value k) v]))
        alias (some-> (options :as) token-value)
        as-alias (some-> (options :as-alias) token-value)
        refer (when-let [v (options :refer)]
                (when (= :vector (n/tag v))
                  (vec (filter symbol? (map token-value (code-children v))))))]
    (cond-> {}
      (symbol? alias) (assoc :as alias)
      (symbol? as-alias) (assoc :as-alias as-alias)
      (seq refer) (assoc :refer refer))))

(defn- libspec-entries
  "Required namespaces of one :require or :use entry, each a map of :ns,
  :line, and when present :as and :refer. Handles symbols, libspec vectors,
  and prefix lists."
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

(defn ns-info
  [forms]
  (when-let [ns-form (first (filter #(= "ns" (head-symbol %))
                              (code-children forms)))]
    {:ns (token-value (unwrap-meta (second (code-children ns-form))))
     :line (:row (meta ns-form))
     :requires (vec
                 (for [clause (code-children ns-form)
                       :when (#{:require :use}
                              (some-> (first (code-children clause))
                                token-value))
                       entry (rest (code-children clause))
                       required (libspec-entries entry)]
                   required))}))
