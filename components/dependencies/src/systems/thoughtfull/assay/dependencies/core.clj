(ns systems.thoughtfull.assay.dependencies.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.dependencies.cohesion :as cohesion]
   [systems.thoughtfull.assay.dependencies.connascence :as connascence]
   [systems.thoughtfull.assay.dependencies.names :as names]))

(def default-rules
  {:stable-dependencies :error
   :cycles :error
   :new-dependencies :warning
   :unused-interface :warning
   :connascence-of-position {:max 3 :level :warning}
   :duplicate-code {:min-forms 30 :level :warning}
   :merge-candidates {:max-size 0.25 :level :warning}})

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
        shared-keywords (connascence/shared-keywords measurements)]
    {:edges edges
     :workspace workspace
     :used (:used cohesion-analysis)
     :unused-interface (:unused cohesion-analysis)
     :bricks (vec
               (for [{:keys [brick] :as m} measurements
                     :let [brick-name (:name brick)]]
                 (update m :metrics merge
                   (dependency-metrics workspace (segments brick-name) m
                     (set (map :interface (interfaces-of brick-name)))
                     (set (map :from (dependents-of brick-name))))
                   (get-in cohesion-analysis [:metrics brick-name])
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

(defn- strongly-connected
  "Strongly connected components of a graph (node to successors) with more
  than one node, by Tarjan's algorithm."
  [graph]
  (let [state (atom {:index 0 :stack [] :on-stack #{} :indices {} :low {}
                     :components []})]
    (letfn [(connect [v]
              (swap! state #(-> %
                              (assoc-in [:indices v] (:index %))
                              (assoc-in [:low v] (:index %))
                              (update :index inc)
                              (update :stack conj v)
                              (update :on-stack conj v)))
              (doseq [w (graph v)]
                (cond
                  (not (contains? (:indices @state) w))
                  (do (connect w)
                    (swap! state update-in [:low v] min
                      (get-in @state [:low w])))
                  ((:on-stack @state) w)
                  (swap! state update-in [:low v] min
                    (get-in @state [:indices w]))))
              (when (= (get-in @state [:low v]) (get-in @state [:indices v]))
                (pop-component v)))
            (pop-component [v]
              (loop [component []]
                (let [w (peek (:stack @state))]
                  (swap! state #(-> %
                                  (update :stack pop)
                                  (update :on-stack disj w)))
                  (if (= w v)
                    (when (< 1 (count (conj component w)))
                      (swap! state update :components conj
                        (vec (sort (conj component w)))))
                    (recur (conj component w))))))]
      (doseq [v (sort (keys graph))
              :when (not (contains? (:indices @state) v))]
        (connect v))
      (:components @state))))

(defn- cycle-violations
  [level {:keys [bricks edges]}]
  (let [index (brick-index bricks)
        graph (reduce (fn [g {:keys [from to]}]
                        (update g from (fnil conj #{}) to))
                (zipmap (keys index) (repeat #{}))
                edges)]
    (for [component (strongly-connected graph)
          brick-name component
          :let [cycle-text (str/join ", " component)
                location (:location (first (filter #(and (= brick-name (:from %))
                                                      (some #{(:to %)} component))
                                             edges)))]]
      (cond-> {:scope :dependency
               :brick (get-in index [brick-name :brick])
               :metric :dependency-cycle
               :label "Dependency cycle"
               :subject cycle-text
               :value (count component)
               :level level
               :rule {:rule :cycles}
               :message (str "is in a dependency cycle with " cycle-text)}
        location (assoc :location location)))))

(defn- unused-interface-violations
  [level {:keys [unused-interface]}]
  (for [{:keys [brick name file line]} unused-interface]
    {:scope :dependency
     :brick brick
     :metric :unused-interface
     :label "Interface definition"
     :subject (str name)
     :level level
     :rule {:rule :unused-interface}
     :location {:file file :line line :name (str name)}
     :message "is not used by any other brick"}))

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

(defn- level
  "A rule's level: the rule itself, or its :level when it has settings."
  [rule]
  (if (map? rule) (:level rule) rule))

(defn check
  [rules {:keys [workspace bricks used] :as analysis}]
  (let [{:keys [stable-dependencies cycles unused-interface
                connascence-of-position duplicate-code
                merge-candidates]} rules]
    (vec (concat
           (when stable-dependencies
             (stable-dependency-violations stable-dependencies analysis))
           (when cycles
             (cycle-violations cycles analysis))
           (when unused-interface
             (unused-interface-violations unused-interface analysis))
           (when (level connascence-of-position)
             (connascence/position-violations connascence-of-position
               workspace bricks used))
           (when (level duplicate-code)
             (connascence/algorithm-violations duplicate-code bricks))
           (when (level merge-candidates)
             (merge-candidate-violations merge-candidates analysis))))))

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
  "Edges to draw in red: stable-dependency violations and cycles, as
  [from to] pairs."
  [violations]
  (into #{}
    (mapcat (fn [{:keys [metric brick subject]}]
              (case metric
                :stable-dependencies [[(:name brick) subject]]
                :dependency-cycle (let [members (set (str/split subject #", "))]
                                    (for [to members
                                          :when (not= to (:name brick))]
                                      [(:name brick) to]))
                nil)))
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
                    violations)]
    (str/join "\n"
      (concat
        ["graph TD"]
        (map #(node (ids (:name (:brick %))) (:brick %)) bricks)
        (for [[from to] pairs]
          (str "  " (ids from) (if (new-edges [from to]) " -.-> " " --> ")
            (ids to)))
        (for [[i pair] (map-indexed vector pairs)
              :when (red pair)]
          (str "  linkStyle " i " stroke:#d1242f,stroke-width:2px"))))))
