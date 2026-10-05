(ns systems.thoughtfull.assay.baseline.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.metrics.interface :as metrics]))

(defn- fmt
  [x]
  (if (== x (Math/rint x))
    (str (long x))
    (format "%.1f" (double x))))

(defmulti evaluate-change
  "Evaluate a change rule against a brick's base value (nil for a new brick)
  and head value. Returns nil if the rule passes, or a map of :limit and
  :message."
  (fn [rule _base _head] (:rule rule)))

(defmethod evaluate-change :max-increase
  [{limit :value} base head]
  (let [delta (- head (or base 0))]
    (when (> delta limit)
      {:limit limit
       :message (str "increased by " (fmt delta)
                  (if base
                    (str " (" (fmt base) " to " (fmt head) ")")
                    " in a new brick")
                  ", above the maximum increase of " (fmt limit))})))

(defmethod evaluate-change :max-increase-percent
  [{limit :value} base head]
  (when (and base (pos? base))
    (let [percent (* 100.0 (/ (- head base) base))]
      (when (> percent limit)
        {:limit limit
         :message (str "increased by " (fmt percent) "% (" (fmt base) " to "
                    (fmt head) "), above the maximum increase of "
                    (fmt limit) "%")}))))

(defmethod evaluate-change :default
  [rule _ _]
  (throw (ex-info (str "Unknown change rule: " (pr-str (:rule rule)))
           {:rule rule})))

(defn changed-bricks
  [measurements changed-files]
  (into #{}
    (for [{:keys [brick]} measurements
          :let [prefix (str (:dir brick) "/src/")]
          :when (some #(str/starts-with? % prefix) changed-files)]
      (:name brick))))

(defn- violation-key
  "Identifies a violation across reports: a function or dependency is
  identified by its :subject, not by its line, which moves."
  [{:keys [brick metric rule subject]}]
  [(:name brick) metric rule subject])

(defn- status
  "A historical violation, from history rather than code, is never new."
  [base-keys changed violation]
  (cond
    (:historical? violation) :existing
    (base-keys (violation-key violation)) :existing
    (changed (:name (:brick violation))) :new
    :else :indirect))

(defn- base-value
  "The violation's metric in base: the brick's metric, or for a function
  violation, the same function's."
  [base-metrics base-functions {:keys [scope brick metric subject]}]
  (case scope
    :function (get-in base-functions [[(:name brick) subject] metric])
    :dependency nil
    (get-in base-metrics [(:name brick) metric])))

(defn- change-violations
  [head base-metrics changed change-thresholds]
  (for [{:keys [brick metrics]} (:bricks head)
        :when (changed (:name brick))
        [metric rules] change-thresholds
        rule rules
        :let [base-value (get-in base-metrics [(:name brick) metric])
              value (get metrics metric)
              result (when (some? value)
                       (evaluate-change rule base-value value))]
        :when result]
    (merge {:scope :brick
            :brick brick
            :metric metric
            :value value
            :base-value base-value
            :rule rule
            :level (:level rule :error)
            :status :new
            :change? true}
      result)))

(defn- new-dependency-violations
  [base head level]
  (when level
    (let [base-edges (set (map (juxt :from :to) (:edges base)))
          index (into {} (map (juxt (comp :name :brick) :brick)) (:bricks head))]
      (for [{:keys [from to interface location]} (:edges head)
            :when (not (base-edges [from to]))]
        {:scope :dependency
         :brick (index from)
         :metric :new-dependency
         :label "New dependency"
         :subject to
         :level level
         :rule {:rule :new-dependencies}
         :status :new
         :location location
         :message (str "now depends on " to
                    (when (not= to interface)
                      (str " (through interface " interface ")")))}))))

(defn compare-reports
  [base head changed-files {:keys [changes new-dependencies]}]
  (let [changed (changed-bricks (:bricks head) changed-files)
        base-keys (set (map violation-key (:violations base)))
        head-keys (set (map violation-key (:violations head)))
        base-metrics (into {}
                       (map (juxt (comp :name :brick) :metrics))
                       (:bricks base))
        base-functions (into {}
                         (for [{:keys [brick functions]} (:bricks base)
                               function functions]
                           [[(:name brick) (metrics/function-id function)] function]))
        violations (for [v (:violations head)]
                     (assoc v
                       :status (status base-keys changed v)
                       :base-value (base-value base-metrics base-functions v)))]
    (assoc head
      :violations (vec (concat violations
                         (change-violations head base-metrics changed changes)
                         (new-dependency-violations base head
                           new-dependencies)))
      :comparison {:changed-bricks changed
                   :base-metrics base-metrics
                   :resolved (vec (remove (comp head-keys violation-key)
                                    (:violations base)))})))
