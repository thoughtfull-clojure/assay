(ns systems.thoughtfull.assay.dependencies.co-change
  "Co-change coupling from Git history: bricks that keep changing in the
  same commits are coupled, whatever their requires say. The pairs worth
  reporting are those with no dependency path between them, which is
  coupling the source doesn't show.")

(defn- brick-of
  "The name of the brick whose directory contains file, or nil."
  [bricks file]
  (some (fn [{:keys [dir name]}]
          (when (.startsWith ^String file (str dir "/")) name))
    bricks))

(defn- touched
  "Each commit's set of brick names, leaving out commits that touch no brick
  or more than max-bricks, such as a reformat or a rename across the
  workspace."
  [bricks commits max-bricks]
  (for [files commits
        :let [names (into #{} (keep #(brick-of bricks %)) files)]
        :when (<= 1 (count names) max-bricks)]
    names))

(defn- reachable
  "The set of bricks reachable from brick along graph."
  [graph brick]
  (loop [frontier [brick]
         seen #{}]
    (if-let [b (peek frontier)]
      (let [next-bricks (remove seen (graph b))]
        (recur (into (pop frontier) next-bricks) (into seen next-bricks)))
      seen)))

(defn- connected-fn
  "A predicate of two brick names: true when either depends on the other,
  directly or not."
  [edges]
  (let [graph (reduce (fn [g {:keys [from to]}]
                        (update g from (fnil conj #{}) to))
                {}
                edges)
        reach (memoize #(reachable graph %))]
    (fn [a b]
      (or (contains? (reach a) b) (contains? (reach b) a)))))

(defn pairs
  "Pairs of bricks that changed together, as maps of :bricks (the two names,
  the less changed first), :shared (commits touching both), :changes
  (commits touching the less changed), and :strength (shared / changes)."
  [bricks commits max-bricks]
  (let [changes (touched bricks commits max-bricks)
        counts (frequencies (mapcat seq changes))
        shared (frequencies
                 (for [names changes
                       a names
                       b names
                       :when (neg? (compare a b))]
                   [a b]))]
    (for [[[a b] n] shared
          :let [[fewer more] (sort-by (juxt counts identity) [a b])]]
      {:bricks [fewer more]
       :shared n
       :changes (counts fewer)
       :strength (/ n (double (counts fewer)))})))

(defn violations
  "Hidden co-change coupling: pairs that changed together in at least
  min-shared commits, with at least min-strength, and no dependency path
  between them. Each is reported on the less changed brick, and marked
  :historical?, since it comes from history rather than from the code."
  [{:keys [min-shared min-strength max-bricks-per-commit level]}
   {:keys [bricks edges commits]}]
  (let [index (into {} (map (juxt (comp :name :brick) :brick)) bricks)
        connected? (connected-fn edges)]
    (for [{[a b] :bricks :keys [shared changes strength]}
          (sort-by :bricks (pairs (map :brick bricks) commits
                             max-bricks-per-commit))
          :when (and (>= shared min-shared)
                  (>= strength min-strength)
                  (not (connected? a b)))]
      {:scope :dependency
       :brick (index a)
       :metric :co-change
       :label "Co-change"
       :subject b
       :value strength
       :limit min-strength
       :level level
       :rule {:rule :co-change}
       :historical? true
       :message (str "with " b " in " shared " of its " changes
                  " commits (" (Math/round (* 100 strength)) "%), though"
                  " neither depends on the other")})))
