(ns systems.thoughtfull.assay.dependencies.core
  (:require
   [clojure.string :as str]))

(def default-rules
  {:stable-dependencies :error
   :cycles :error
   :new-dependencies :warning})

(defn- fmt
  [x]
  (str/replace (format "%.2f" (double x)) #"\.?0+$" ""))

;; Namespaces to interfaces

(defn- segment
  "The first namespace segment after top-ns, or nil if ns is not in the
  workspace."
  [top-ns ns]
  (let [prefix (str top-ns ".")
        ns (str ns)]
    (when (and top-ns (str/starts-with? ns prefix))
      (first (str/split (subs ns (count prefix)) #"\.")))))

(defn- own-segment
  "The segment of most of a brick's namespaces: its interface name, for a
  component."
  [top-ns {:keys [sources]}]
  (some->> (keep #(segment top-ns (:ns %)) sources)
    frequencies
    (sort-by (comp - val))
    ffirst))

(defn- interface-ns?
  [{:keys [top-namespace interface-ns]} brick-segment ns]
  (let [ns (str ns)
        interface (str top-namespace "." brick-segment "." interface-ns)]
    (or (= ns interface) (str/starts-with? ns (str interface ".")))))

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
                               :let [s (segment top-ns ns)]
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
  (let [total (reduce + (map :forms sources))
        interface (reduce + (map :forms (filter #(interface-ns? workspace own
                                                   (:ns %))
                                          sources)))]
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
  (let [segments (into {}
                   (map (juxt (comp :name :brick)
                          #(own-segment top-namespace %)))
                   measurements)
        implementers (reduce (fn [acc {:keys [brick]}]
                               (if (= :component (:type brick))
                                 (update acc (segments (:name brick))
                                   (fnil conj []) (:name brick))
                                 acc))
                       {}
                       measurements)
        edges (edges top-namespace measurements segments implementers)
        interfaces-of (group-by :from edges)
        dependents-of (group-by :to edges)]
    {:edges edges
     :bricks (vec
               (for [{:keys [brick] :as m} measurements
                     :let [brick-name (:name brick)]]
                 (update m :metrics merge
                   (dependency-metrics workspace (segments brick-name) m
                     (set (map :interface (interfaces-of brick-name)))
                     (set (map :from (dependents-of brick-name)))))))}))

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

(defn check
  [{:keys [stable-dependencies cycles]} analysis]
  (vec (concat
         (when stable-dependencies
           (stable-dependency-violations stable-dependencies analysis))
         (when cycles
           (cycle-violations cycles analysis)))))
