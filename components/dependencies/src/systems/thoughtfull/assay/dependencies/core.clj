(ns systems.thoughtfull.assay.dependencies.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.dependencies.co-change :as co-change]
   [systems.thoughtfull.assay.dependencies.cohesion :as cohesion]
   [systems.thoughtfull.assay.dependencies.connascence :as connascence]
   [systems.thoughtfull.assay.dependencies.libraries :as libraries]
   [systems.thoughtfull.assay.dependencies.names :as names]))

(def default-rules
  {:stable-dependencies :error
   :new-dependencies :warning
   :connascence-of-position {:max 3 :level :warning}
   :duplicate-code {:min-forms 30 :level :warning}
   :merge-candidates {:max-size 0.25 :level :warning}
   :co-change {:since "12 months" :min-shared 5 :min-strength 0.5
               :max-bricks-per-commit 5 :level :warning}
   :library-spread {:max-bricks 1 :level :warning}
   :mutable-state :warning})

(defn merge-rules
  "Merge configured rules over the defaults. A map-valued rule merges key
  by key, so {:duplicate-code {:min-forms 50}} keeps the default level."
  [rules]
  (merge-with (fn [default configured]
                (if (and (map? default) (map? configured))
                  (merge default configured)
                  configured))
    default-rules rules))

(defn- fmt
  [x]
  (str/replace (format "%.2f" (double x)) #"\.?0+$" ""))

;; Edges and metrics

(defn- edges
  "Edges from each brick to the components implementing each interface it
  requires, located at the first require."
  [top-ns measurements segments implementers]
  (vec
    (for [{:keys [brick sources]} measurements
          :let [own (segments (:name brick))
                requires (for [{:keys [file requires]} sources
                               {:keys [ns line]} requires
                               :let [s (names/segment top-ns ns)]
                               :when (and s (not= s own))]
                           [s {:file file :line line}])
                firsts (reduce (fn [acc [s location]]
                                 (if (contains? acc s) acc (assoc acc s location)))
                         (sorted-map)
                         requires)]
          [interface location] firsts
          to (implementers interface)]
      {:from (:name brick) :to to :interface interface :location location})))

(defn- abstractness
  [workspace own {:keys [brick sources]}]
  (let [definitions #(count (mapcat :definitions %))
        total (definitions sources)
        interface (definitions (filter #(names/interface-ns? workspace own
                                          (:ns %))
                                 sources))]
    (cond
      (= :base (:type brick)) 0.0
      (zero? total) nil
      :else (- 1.0 (/ interface (double total))))))

(defn- dependency-metrics
  [workspace own measurement efferent afferent]
  (let [ce (count efferent)
        ca (count afferent)
        instability (when (pos? (+ ca ce)) (/ ce (double (+ ca ce))))]
    {:afferent ca
     :efferent ce
     :instability instability
     :abstractness (abstractness workspace own measurement)}))

(defn analyze
  [{:keys [top-namespace] :as workspace} measurements]
  (let [segments (names/segments top-namespace measurements)
        implementers (reduce (fn [acc {:keys [brick]}]
                               (if (= :component (:type brick))
                                 (update acc (segments (:name brick))
                                   (fnil conj []) (:name brick))
                                 acc))
                       {}
                       measurements)
        edges (edges top-namespace measurements segments implementers)
        interfaces-of (group-by :from edges)
        dependents-of (group-by :to edges)
        cohesion-analysis (cohesion/analyze workspace measurements)
        shared-keywords (connascence/shared-keywords measurements)
        library-analysis (libraries/analyze top-namespace measurements)]
    {:edges edges
     :workspace workspace
     :used (:used cohesion-analysis)
     :libraries (:libraries library-analysis)
     :bricks (vec
               (for [{:keys [brick] :as m} measurements
                     :let [brick-name (:name brick)]]
                 (update m :metrics merge
                   (dependency-metrics workspace (segments brick-name) m
                     (set (map :interface (interfaces-of brick-name)))
                     (set (map :from (dependents-of brick-name))))
                   (get-in cohesion-analysis [:metrics brick-name])
                   (get-in library-analysis [:metrics brick-name])
                   {:shared-keywords (shared-keywords brick-name)})))}))

;; Checks

(defn- brick-index
  [bricks]
  (into {} (map (juxt (comp :name :brick) identity)) bricks))

(defn- stable-dependency-violations
  [level {:keys [bricks edges]}]
  (let [index (brick-index bricks)
        instability #(get-in index [% :metrics :instability])]
    (for [{:keys [from to location]} edges
          :let [i-from (instability from)
                i-to (instability to)]
          :when (and i-from i-to (> i-to i-from))]
      {:scope :dependency
       :brick (get-in index [from :brick])
       :metric :stable-dependencies
       :label "Stable dependencies"
       :subject to
       :value i-to
       :limit i-from
       :level level
       :rule {:rule :stable-dependencies}
       :location location
       :message (str "depends on " to " (instability " (fmt i-to)
                  "), which is less stable than " from " ("
                  (fmt i-from) ")")})))

(defn- merge-candidate-violations
  "Components with one dependent, itself a component, and at most max-size
  times its forms. Moving one into a base would go against Polylith, so a
  base's components don't count."
  [{:keys [max-size level]} {:keys [bricks edges]}]
  (let [index (brick-index bricks)
        dependents (update-vals (group-by :to edges) #(distinct (map :from %)))]
    (for [{:keys [brick metrics]} bricks
          :let [[dependent & more] (dependents (:name brick))
                other (index dependent)
                size (when (and dependent (not more)
                             (= :component (:type brick))
                             (= :component (get-in other [:brick :type]))
                             (pos? (get-in other [:metrics :forms] 0)))
                       (/ (:forms metrics 0)
                         (double (get-in other [:metrics :forms]))))]
          :when (and size (<= size max-size))]
      {:scope :dependency
       :brick brick
       :metric :merge-candidate
       :label "Merge candidate"
       :subject dependent
       :value size
       :limit max-size
       :level level
       :rule {:rule :merge-candidates}
       :message (str "is used only by " dependent ", and has "
                  (Math/round (* 100 size)) "% as many forms; consider"
                  " merging it into " dependent)})))

(def ^:private mutable-state-text
  {:atom "defines an atom"
   :ref "defines a ref"
   :agent "defines an agent"
   :volatile "defines a volatile"
   :dynamic "defines a dynamic var"
   :alter-var-root "calls alter-var-root on"})

(defn- mutable-state-violations
  "Mutable state in components, which bases, as the shell, may hold. State
  in an interface namespace is shared with every brick that uses it."
  [level {:keys [workspace bricks]}]
  (let [segments (names/segments (:top-namespace workspace) bricks)]
    (for [{:keys [brick sources]} bricks
          :when (= :component (:type brick))
          {:keys [ns file mutable-state]} sources
          {:keys [name line kind]} mutable-state
          :let [interface? (names/interface-ns? workspace
                             (segments (:name brick)) ns)]]
      {:scope :dependency
       :brick brick
       :metric :mutable-state
       :label "Mutable state"
       :subject (str name)
       :level level
       :rule {:rule :mutable-state}
       :location {:file file :line line :name (str name)}
       :message (str (mutable-state-text kind)
                  (when (= :alter-var-root kind) (str " " name))
                  (if interface?
                    (str " in its interface, so every brick that uses it"
                      " shares the state")
                    ", state hidden from the functions that use it"))})))

(defn- level
  "A rule's level: the rule itself, or its :level when it has settings."
  [rule]
  (if (map? rule) (:level rule) rule))

(defn check
  [rules {:keys [workspace bricks used] :as analysis}]
  (let [{:keys [stable-dependencies
                connascence-of-position duplicate-code
                merge-candidates co-change library-spread
                mutable-state]} rules]
    (vec (concat
           (when stable-dependencies
             (stable-dependency-violations stable-dependencies analysis))
           (when (level connascence-of-position)
             (connascence/position-violations connascence-of-position
               workspace bricks used))
           (when (level duplicate-code)
             (connascence/algorithm-violations duplicate-code bricks))
           (when (level merge-candidates)
             (merge-candidate-violations merge-candidates analysis))
           (when (level co-change)
             (co-change/violations co-change analysis))
           (when (level library-spread)
             (libraries/violations library-spread analysis))
           (when mutable-state
             (mutable-state-violations mutable-state analysis))))))

(defn neighbors
  [bricks edges]
  (let [uses (group-by :from edges)
        used-by (group-by :to edges)
        names (fn [xs k] (vec (sort (distinct (map k xs)))))]
    (for [{:keys [brick]} bricks
          :let [brick-name (:name brick)]]
      {:brick brick
       :depends-on (names (uses brick-name) :to)
       :depended-on-by (names (used-by brick-name) :from)})))

;; Graph

(defn- problem-edges
  "Edges to draw in red, from stable-dependency violations, as [from to]
  pairs."
  [violations]
  (into #{}
    (comp (filter #(= :stable-dependencies (:metric %)))
      (map (juxt (comp :name :brick) :subject)))
    violations))

(defn- node
  [id {:keys [name type]}]
  (if (= :base type)
    (str "  " id "([\"" name "\"])")
    (str "  " id "[\"" name "\"]")))

(defn mermaid
  [bricks edges violations]
  (let [ids (into {} (map-indexed (fn [i {:keys [brick]}]
                                    [(:name brick) (str "b" i)])
                       bricks))
        pairs (distinct (map (juxt :from :to) edges))
        red (problem-edges violations)
        new-edges (into #{} (comp (filter #(= :new-dependency (:metric %)))
                              (map (juxt (comp :name :brick) :subject)))
                    violations)
        co-changes (distinct (for [{:keys [metric brick subject]} violations
                                   :when (= :co-change metric)]
                               [(:name brick) subject]))]
    (str/join "\n"
      (concat
        ["graph TD"]
        (map #(node (ids (:name (:brick %))) (:brick %)) bricks)
        (for [[from to] pairs]
          (str "  " (ids from) (if (new-edges [from to]) " -.-> " " --> ")
            (ids to)))
        (for [[a b] co-changes]
          (str "  " (ids a) " -.- " (ids b)))
        (for [[i pair] (map-indexed vector pairs)
              :when (red pair)]
          (str "  linkStyle " i " stroke:#d1242f,stroke-width:2px"))
        (for [i (range (count pairs) (+ (count pairs) (count co-changes)))]
          (str "  linkStyle " i
            " stroke:#b26b00,stroke-width:2px,stroke-dasharray:2 4"))))))
