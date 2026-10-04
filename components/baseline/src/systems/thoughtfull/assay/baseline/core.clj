(ns systems.thoughtfull.assay.baseline.core
  (:require
   [clojure.string :as str]))

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
  [{:keys [brick metric rule]}]
  [(:name brick) metric rule])

(defn- status
  [base-keys changed violation]
  (cond
    (base-keys (violation-key violation)) :existing
    (changed (:name (:brick violation))) :new
    :else :indirect))

(defn- change-violations
  [head base-metrics changed change-thresholds]
  (for [{:keys [brick metrics locations]} (:bricks head)
        :when (changed (:name brick))
        [metric rules] change-thresholds
        rule rules
        :let [base-value (get-in base-metrics [(:name brick) metric])
              value (get metrics metric)
              result (when (some? value)
                       (evaluate-change rule base-value value))]
        :when result]
    (cond-> (merge {:brick brick
                    :metric metric
                    :value value
                    :base-value base-value
                    :rule rule
                    :level (:level rule :error)
                    :status :new
                    :change? true}
              result)
      (get locations metric) (assoc :location (get locations metric)))))

(defn compare-reports
  [base head changed-files change-thresholds]
  (let [changed (changed-bricks (:bricks head) changed-files)
        base-keys (set (map violation-key (:violations base)))
        head-keys (set (map violation-key (:violations head)))
        base-metrics (into {}
                       (map (juxt (comp :name :brick) :metrics))
                       (:bricks base))
        violations (for [v (:violations head)]
                     (assoc v
                       :status (status base-keys changed v)
                       :base-value (get-in base-metrics
                                     [(:name (:brick v)) (:metric v)])))]
    (assoc head
      :violations (into (vec violations)
                    (change-violations head base-metrics changed
                      change-thresholds))
      :comparison {:changed-bricks changed
                   :base-metrics base-metrics
                   :resolved (vec (remove (comp head-keys violation-key)
                                    (:violations base)))})))
