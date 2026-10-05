(ns systems.thoughtfull.assay.dependencies.cohesion
  "Cohesion within bricks, from the symbols each definition references.

  - Cohesion: references to the brick's own namespaces / references to any
    workspace namespace. References to other libraries don't count.
  - Clusters: groups of the brick's implementation definitions (outside its
    interface) that don't reference each other (LCOM4). More than one
    suggests unrelated responsibilities.
  - Unused interface: interface definitions no other brick references."
  (:require
   [systems.thoughtfull.assay.dependencies.names :as names]))

(defn- resolver
  "A function from a symbol referenced in source to [namespace name], or
  nil if it doesn't refer to a definition in a known namespace."
  [{:keys [ns requires definitions]}]
  (let [aliases (into {} (keep (fn [{:keys [as] :as r}]
                                 (when as [as (:ns r)])))
                  requires)
        required (set (map :ns requires))
        refers (into {} (for [{:keys [refer] :as r} requires
                              s refer]
                          [s (:ns r)]))
        own (set (map :name definitions))]
    (fn [sym]
      (if-let [qualifier (some-> (namespace sym) symbol)]
        (when-let [target (or (aliases qualifier) (required qualifier))]
          [target (symbol (name sym))])
        (cond
          (own sym) [ns sym]
          (refers sym) [(refers sym) sym])))))

(defn- references
  "Every resolved reference in a source: maps of :from (a definition name),
  :ns, and :name."
  [source]
  (let [resolve (resolver source)]
    (for [{:keys [references] :as definition} (:definitions source)
          sym references
          :let [[target-ns target-name] (resolve sym)]
          :when target-ns]
      {:from [(:ns source) (:name definition)]
       :ns target-ns
       :name target-name})))

(defn- cohesion
  [top-ns own refs]
  (let [workspace-refs (keep #(names/segment top-ns (:ns %)) refs)
        internal (count (filter #{own} workspace-refs))
        total (count workspace-refs)]
    (when (pos? total)
      (/ internal (double total)))))

(defn- connected-groups
  "The number of connected groups of nodes, given undirected edges."
  [nodes edges]
  (let [neighbors (reduce (fn [g [a b]]
                            (-> g (update a (fnil conj #{}) b)
                              (update b (fnil conj #{}) a)))
                    {}
                    edges)]
    (loop [unvisited (set nodes)
           groups 0]
      (if-let [start (first unvisited)]
        (recur (loop [frontier [start]
                      unvisited (disj unvisited start)]
                 (if-let [node (peek frontier)]
                   (let [next-nodes (filter unvisited (neighbors node))]
                     (recur (into (pop frontier) next-nodes)
                       (reduce disj unvisited next-nodes)))
                   unvisited))
          (inc groups))
        groups))))

(defn- clusters
  [workspace own sources refs]
  (let [implementation (remove #(names/interface-ns? workspace own (:ns %))
                         sources)
        nodes (set (for [{:keys [ns definitions]} implementation
                         {:keys [name]} definitions]
                     [ns name]))
        edges (for [{:keys [from ns name]} refs
                    :let [to [ns name]]
                    :when (and (nodes from) (nodes to) (not= from to))]
                [from to])]
    (when (seq nodes)
      (connected-groups nodes edges))))

(defn- interface-definitions
  [workspace own {:keys [sources]}]
  (for [{:keys [ns file definitions]} sources
        :when (names/interface-ns? workspace own ns)
        {:keys [name line]} definitions]
    {:segment own :ns ns :name name :file file :line line}))

(defn analyze
  "Cohesion metrics for each brick, by name; the unused interface
  definitions, each a map of :brick, :name, :file, and :line; and :used,
  the set of [segment name] interface definitions other bricks refer to."
  [{:keys [top-namespace] :as workspace} measurements]
  (let [segments (names/segments top-namespace measurements)
        refs-by-brick (into {}
                        (for [{:keys [brick sources]} measurements]
                          [(:name brick) (vec (mapcat references sources))]))
        used (into #{}
               (for [[brick-name refs] refs-by-brick
                     {:keys [ns name]} refs
                     :let [s (names/segment top-namespace ns)]
                     :when (and s (not= s (segments brick-name)))]
                 [s name]))
        unused (for [{:keys [brick] :as m} measurements
                     :when (= :component (:type brick))
                     d (interface-definitions workspace
                         (segments (:name brick)) m)
                     :when (not (used [(:segment d) (:name d)]))]
                 (assoc d :brick brick))]
    {:used used
     :metrics (into {}
                (for [{:keys [brick sources]} measurements
                      :let [brick-name (:name brick)
                            own (segments brick-name)
                            refs (refs-by-brick brick-name)]]
                  [brick-name
                   {:cohesion (cohesion top-namespace own refs)
                    :clusters (clusters workspace own sources refs)
                    :unused-interface
                    (when (= :component (:type brick))
                      (count (filter #(= brick (:brick %)) unused)))}]))
     :unused (vec unused)}))
