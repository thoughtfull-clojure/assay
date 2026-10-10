(ns systems.thoughtfull.assay.thresholds.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.metrics.interface :as metrics]))

(def default-config
  {:brick-thresholds
   {:mean-function-complexity [{:rule :std-devs :value 2 :level :warning}]
    :abstractness [{:rule :min :value 0.5 :level :warning
                    :types #{:component}}]}
   :function-thresholds
   {:complexity [{:rule :max :value 10 :level :error}]
    :depth [{:rule :max :value 8 :level :warning}]
    :forms [{:rule :max :value 150 :level :warning}]
    :params [{:rule :max :value 4 :level :warning}]}})

(defn merge-config
  [config]
  {:brick-thresholds (merge (:brick-thresholds default-config)
                       (:brick-thresholds config))
   :function-thresholds (merge (:function-thresholds default-config)
                          (:function-thresholds config))})

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
    (str/replace (format "%.2f" (double x)) #"\.?0+$" "")))

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

(defn- applies?
  [rule brick]
  (or (nil? (:types rule)) (contains? (:types rule) (:type brick))))

(defn- brick-violations
  [thresholds measurements]
  (for [measurement measurements
        [metric rules] thresholds
        rule rules
        :let [brick (:brick measurement)
              value (get-in measurement [:metrics metric])
              result (when (and (some? value) (applies? rule brick))
                       (evaluate rule value
                         (peer-values measurements measurement metric)))]
        :when result]
    (merge {:scope :brick
            :brick brick
            :metric metric
            :value value
            :rule rule
            :level (:level rule :error)}
      result)))

(def ^:private function-rules
  #{:max :min})

(defn- check-function-rule
  [metric rule]
  (when-not (function-rules (:rule rule))
    (throw (ex-info (str "Function thresholds support only :max and :min, not "
                      (pr-str (:rule rule)) " (for " metric ")")
             {:metric metric :rule rule}))))

(defn- function-violations
  [thresholds measurements]
  (doseq [[metric rules] thresholds
          rule rules]
    (check-function-rule metric rule))
  (for [{:keys [brick functions]} measurements
        function functions
        [metric rules] thresholds
        rule rules
        :let [value (get function metric)
              result (when (some? value) (evaluate rule value nil))]
        :when result]
    (merge {:scope :function
            :brick brick
            :metric metric
            :value value
            :rule rule
            :level (:level rule :error)
            :subject (metrics/function-id function)
            :location {:file (:file function)
                       :line (if (= :depth metric)
                               (:depth-line function)
                               (:line function))
                       :name (:name function)}}
      result)))

(defn check
  [{:keys [brick-thresholds function-thresholds]} measurements]
  (vec (concat (brick-violations brick-thresholds measurements)
         (function-violations function-thresholds measurements))))

(defn worse-level
  [a b]
  (if (some #{:error} [a b]) :error (or a b)))

(defn- excess
  "How far past its limit a violation's value is, relative to the limit, so
  metrics of different scales compare. 0 when either is unknown."
  [{:keys [value limit]}]
  (if (and (number? value) (number? limit))
    (/ (Math/abs (double (- value limit)))
      (if (zero? limit) 1.0 (Math/abs (double limit))))
    0.0))

(defn by-severity
  [violations]
  (sort-by (juxt #(if (= :error (:level %)) 0 1)
             (comp - excess)
             (comp :name :brick)
             #(get-in % [:location :file] "")
             #(get-in % [:location :line] 0))
    violations))
