(ns systems.thoughtfull.assay.dependencies.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.dependencies.co-change :as co-change]
   [systems.thoughtfull.assay.dependencies.cohesion :as cohesion]
   [systems.thoughtfull.assay.dependencies.connascence :as connascence]
   [systems.thoughtfull.assay.dependencies.errors :as errors]
   [systems.thoughtfull.assay.dependencies.libraries :as libraries]
   [systems.thoughtfull.assay.dependencies.names :as names]
   [systems.thoughtfull.assay.dependencies.tests :as tests]))

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
  (let [total (count (mapcat :definitions sources))
        interface (count (names/interface-definitions workspace own
                           sources))]
    (when (and (= :component (:type brick)) (pos? total))
      (- 1.0 (/ interface (double total))))))

(defn- dependency-metrics
  [workspace own measurement efferent afferent]
  (let [ce (count efferent)
        ca (count afferent)
        instability (when (pos? (+ ca ce)) (/ ce (double (+ ca ce))))
        abstractness (abstractness workspace own measurement)]
    {:afferent ca
     :efferent ce
     :instability instability
     :abstractness abstractness
     :main-sequence-distance (when (and abstractness instability)
                               (Math/abs (- (+ abstractness instability)
                                           1.0)))}))

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
        library-analysis (libraries/analyze top-namespace measurements)
        error-metrics (errors/analyze workspace measurements)
        test-metrics (tests/analyze workspace measurements)]
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
                   (error-metrics brick-name)
                   (test-metrics brick-name)
                   {:shared-keywords (shared-keywords brick-name)})))}))

;; Findings

(defn- brick-index
  [bricks]
  (into {} (map (juxt (comp :name :brick) identity)) bricks))

(defn- unstable-dependencies
  "Each dependency on a brick less stable than the depending one, with the
  difference in instability as its value."
  [{:keys [bricks edges]}]
  (let [index (brick-index bricks)
        instability #(get-in index [% :metrics :instability])]
    (for [{:keys [from to location]} edges
          :let [i-from (instability from)
                i-to (instability to)]
          :when (and i-from i-to (> i-to i-from))]
      {:brick (get-in index [from :brick])
       :subject to
       :value (- i-to i-from)
       :location location
       :message (str "depends on " to " (instability " (fmt i-to)
                  "), which is less stable than " from " ("
                  (fmt i-from) ")")})))

(defn- merge-candidates
  "Components with one dependent, itself a component, with their size as a
  share of the dependent's as their value. Moving one into a base would go
  against Polylith, so a base's components don't count."
  [{:keys [bricks edges]}]
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
          :when size]
      {:brick brick
       :subject dependent
       :value size
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

(defn- mutable-state
  "Mutable state in every brick. State in an interface namespace is shared
  with every brick that uses it."
  [{:keys [workspace bricks]}]
  (let [segments (names/segments (:top-namespace workspace) bricks)]
    (for [{:keys [brick sources]} bricks
          {:keys [ns file mutable-state]} sources
          {:keys [name line kind]} mutable-state
          :let [interface? (names/interface-ns? workspace
                             (segments (:name brick)) ns)]]
      {:brick brick
       :subject (str name)
       :location {:file file :line line :name (str name)}
       :message (str (mutable-state-text kind)
                  (when (= :alter-var-root kind) (str " " name))
                  (if interface?
                    (str " in its interface, so every brick that uses it"
                      " shares the state")
                    ", state hidden from the functions that use it"))})))

(def ^:private handling-text
  {:logs " and logs it"
   :continues " and carries on"})

(defn- broad-catches
  "Broad catch clauses that don't rethrow, in every brick. Bases, at the
  edges, are where a failure's meaning is known. A catch that rethrows
  translates the failure rather than hiding it."
  [{:keys [bricks]}]
  (for [{:keys [brick sources]} bricks
        {:keys [file catches]} sources
        {:keys [line class broad? handling]} catches
        :when (and broad? (not= :rethrows handling))]
    {:brick brick
     :subject (str file ":" line)
     :location {:file file :line line}
     :message (str "catches " class (handling-text handling)
                ", deciding for every caller what a failure means")}))

(def ^:private untyped-text
  {:untyped "throws ex-info without a :type in its data"
   :java "throws a Java exception, which callers can tell apart only by class"})

(defn- untyped-errors
  "Throws that give callers nothing to tell failures apart by, in every
  brick."
  [{:keys [bricks]}]
  (for [{:keys [brick sources]} bricks
        {:keys [file throws]} sources
        {:keys [line kind]} throws
        :when (untyped-text kind)]
    {:brick brick
     :subject (str file ":" line)
     :location {:file file :line line}
     :message (untyped-text kind)}))

(defn- lowest-limit
  "The smallest threshold of settings: the one that a :max metric's values
  pass first."
  [settings]
  (some->> (keep #(get settings %) [:error :warning]) seq (apply min)))

(defn findings
  [settings {:keys [workspace bricks used] :as analysis}]
  (let [{:keys [co-change duplicate-code library-spread]} settings]
    (cond-> {:unstable-dependencies (vec (unstable-dependencies analysis))
             :positional-interface (vec (connascence/positional-interface
                                          workspace bricks used))
             :merge-candidate (vec (merge-candidates analysis))
             :mutable-state (vec (mutable-state analysis))
             :broad-catches (vec (broad-catches analysis))
             :untyped-errors (vec (untyped-errors analysis))
             :boundary-crossings (vec (tests/boundary-crossings analysis))
             :library-spread (vec (libraries/spread
                                    (:allow library-spread) analysis))}
      (lowest-limit duplicate-code)
      (assoc :duplicate-code (vec (connascence/duplicates
                                    (lowest-limit duplicate-code) bricks)))
      co-change
      (assoc :co-change (vec (co-change/findings co-change analysis))))))

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
  "Edges to draw in red, from unstable-dependency violations, as [from to]
  pairs."
  [violations]
  (into #{}
    (comp (filter #(= :unstable-dependencies (:metric %)))
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
        new-edges (into #{} (comp (filter :new?) (map (juxt :from :to))) edges)
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
