(ns systems.thoughtfull.assay.dependencies.cohesion
  "Cohesion within bricks, from the symbols each definition references.

  - Cohesion: references to the brick's own namespaces / references to any
    workspace namespace. References to other libraries don't count."
  (:require
   [systems.thoughtfull.assay.dependencies.names :as names]))

(defn resolver
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

(defn references
  "Every resolved reference in a source: maps of :from ([ns name] of the
  referring definition), :ns, and :name."
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
  "A brick's :cohesion and :workspace-references, the references it is
  computed from."
  [top-ns own refs]
  (let [workspace-refs (keep #(names/segment top-ns (:ns %)) refs)
        internal (count (filter #{own} workspace-refs))
        total (count workspace-refs)]
    {:cohesion (when (pos? total)
                 (/ internal (double total)))
     :workspace-references total}))

(defn analyze
  "Cohesion for each brick, by name, and :used, the set of [segment name]
  interface definitions other bricks refer to."
  [{:keys [top-namespace]} measurements]
  (let [segments (names/segments top-namespace measurements)
        refs-by-brick (into {}
                        (for [{:keys [brick sources]} measurements]
                          [(:name brick) (vec (mapcat references sources))]))
        used (into #{}
               (for [[brick-name refs] refs-by-brick
                     {:keys [ns name]} refs
                     :let [s (names/segment top-namespace ns)]
                     :when (and s (not= s (segments brick-name)))]
                 [s name]))]
    {:used used
     :metrics (into {}
                (for [{:keys [brick]} measurements
                      :let [brick-name (:name brick)
                            own (segments brick-name)
                            refs (refs-by-brick brick-name)]]
                  [brick-name (when (= :component (:type brick))
                                (cohesion top-namespace own refs))]))}))
