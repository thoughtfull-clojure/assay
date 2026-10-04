(ns systems.thoughtfull.assay.thresholds.core)

(def default-thresholds
  {:max-function-complexity [{:rule :max :value 10 :level :error}]
   :max-nesting-depth [{:rule :max :value 8 :level :warning}]
   :cyclomatic-complexity [{:rule :std-devs :value 2 :level :warning}]
   :lines [{:rule :std-devs :value 2 :level :warning}]})

(defn- mean
  [xs]
  (/ (reduce + xs) (double (count xs))))

(defn- std-dev
  "Sample standard deviation."
  [xs]
  (let [m (mean xs)]
    (Math/sqrt (/ (reduce + (map #(Math/pow (- % m) 2) xs))
                 (dec (count xs))))))

(defn- fmt
  [x]
  (if (== x (Math/rint x))
    (str (long x))
    (format "%.1f" (double x))))

(defmulti evaluate
  "Evaluate rule against a brick's value and its peers' values. Returns nil
  if the rule passes, or a map of :limit, :message, and optionally :stats."
  (fn [rule _value _peer-values] (:rule rule)))

(defmethod evaluate :max
  [{limit :value} value _]
  (when (> value limit)
    {:limit limit
     :message (str (fmt value) " is above the maximum of " (fmt limit))}))

(defmethod evaluate :min
  [{limit :value} value _]
  (when (< value limit)
    {:limit limit
     :message (str (fmt value) " is below the minimum of " (fmt limit))}))

(defmethod evaluate :std-devs
  [{k :value :keys [min-peers] :or {min-peers 3}} value peer-values]
  (when (>= (count peer-values) (max 2 min-peers))
    (let [m (mean peer-values)
          sd (std-dev peer-values)
          limit (+ m (* k sd))
          stats {:mean m :std-dev sd :peers (count peer-values)}]
      (when (> value limit)
        {:limit limit
         :stats stats
         :message (if (zero? sd)
                    (str (fmt value) " is above every other brick ("
                      (fmt m) ")")
                    (str (fmt value) " is "
                      (format "%.1f" (/ (- value m) sd))
                      " standard deviations above the mean of "
                      (count peer-values) " other bricks ("
                      (fmt m) " ± " (fmt sd) "), over the limit of "
                      (fmt k)))}))))

(defmethod evaluate :default
  [rule _ _]
  (throw (ex-info (str "Unknown threshold rule: " (pr-str (:rule rule)))
           {:rule rule})))

(defn- peer-values
  [measurements measurement metric]
  (let [{:keys [type name]} (:brick measurement)]
    (for [other measurements
          :let [brick (:brick other)
                v (get-in other [:metrics metric])]
          :when (and (= type (:type brick))
                  (not= name (:name brick))
                  (some? v))]
      v)))

(defn check
  [thresholds measurements]
  (vec
    (for [measurement measurements
          [metric rules] thresholds
          rule rules
          :let [value (get-in measurement [:metrics metric])
                result (when (some? value)
                         (evaluate rule value
                           (peer-values measurements measurement metric)))]
          :when result]
      (cond-> (merge {:brick (:brick measurement)
                      :metric metric
                      :value value
                      :rule rule
                      :level (:level rule :error)}
                result)
        (get-in measurement [:locations metric])
        (assoc :location (get-in measurement [:locations metric]))))))
