(ns systems.thoughtfull.assay.thresholds.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.metrics.interface :as metrics]))

(def ^:private levels
  "Threshold levels, most severe first: a value past both is an error."
  [:error :warning])

(def default-config
  (reduce (fn [config {:keys [key section default options]}]
            (if (or default options)
              (assoc-in config [section key] (merge options default))
              config))
    {}
    metrics/metrics))

;; Config

(def ^:private old-keys
  #{:function-thresholds :brick-thresholds :dependency-rules :io-rules
    :error-handling-rules :test-rules})

(def ^:private section-keys
  (set (map :key metrics/sections)))

(defn- invalid
  [message data]
  (throw (ex-info message (assoc data :type ::invalid-config))))

(defn- check-threshold
  [k level t kind]
  (cond
    (or (nil? t) (number? t)) nil

    (and (map? t) (number? (:std-devs t))
      (every? #{:std-devs :min-peers} (keys t)))
    (when (not= :brick kind)
      (invalid (str "{:std-devs n} applies only to a brick's own value,"
                 " not to " (pr-str k))
        {:metric k :level level}))

    :else
    (invalid (str "The " (name level) " threshold of " (pr-str k)
               " must be a number, {:std-devs n}, or nil, not " (pr-str t))
      {:metric k :level level})))

(defn- check-settings
  [section k settings]
  (let [{:keys [kind options] :as metric} (metrics/metric k)]
    (cond
      (nil? metric)
      (invalid (str "Unknown metric " (pr-str k) " under " (pr-str section))
        {:metric k :section section})

      (not= section (:section metric))
      (invalid (str (pr-str k) " belongs under " (pr-str (:section metric))
                 ", not " (pr-str section))
        {:metric k :section section :belongs (:section metric)})

      (nil? settings) nil

      (not (map? settings))
      (invalid (str "The settings of " (pr-str k) " must be a map or nil,"
                 " not " (pr-str settings))
        {:metric k})

      :else
      (do (doseq [setting (keys settings)
                  :when (not (contains? (into (set levels) (keys options))
                               setting))]
            (invalid (str "Unknown setting " (pr-str setting) " for "
                       (pr-str k))
              {:metric k :setting setting}))
        (doseq [level levels]
          (check-threshold k level (get settings level) kind))))))

(defn- check-config
  [config]
  (when-let [old (seq (filter old-keys (keys config)))]
    (invalid (str "Config keys " (str/join ", " (map pr-str (sort old)))
               " are from an older version of assay. Group metrics by"
               " report section instead, such as {:complexity"
               " {:function-complexity {:error 10}}}; see the README.")
      {:keys (vec old)}))
  (doseq [[section metric-settings] config]
    (when-not (contains? section-keys section)
      (invalid (str "Unknown section " (pr-str section) "; sections are "
                 (str/join ", " (map (comp pr-str :key) metrics/sections)))
        {:section section}))
    (when-not (map? metric-settings)
      (invalid (str (pr-str section) " must map metrics to settings")
        {:section section}))
    (doseq [[k settings] metric-settings]
      (check-settings section k settings))))

(defn merge-config
  [config]
  (check-config config)
  (merge-with (fn [defaults configured]
                (reduce-kv (fn [merged k settings]
                             (assoc merged k
                               (when (some? settings)
                                 (merge (get defaults k) settings))))
                  defaults
                  configured))
    default-config
    config))

(defn settings
  [config k]
  (get-in config [(:section (metrics/metric k)) k]))

;; Evaluation

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

(defn- past?
  [direction limit value]
  (case direction
    :max (> value limit)
    :min (< value limit)))

(defn- limit-text
  [direction limit]
  (str (if (= :max direction) "above the maximum of " "below the minimum of ")
    (fmt limit)))

(defn- first-level
  "The most severe level of settings whose threshold f finds value past,
  as f's result with :level."
  [settings f]
  (some (fn [level]
          (when-some [t (get settings level)]
            (some-> (f t) (assoc :level level))))
    levels))

(defn- peer-noun
  [checks]
  (if (= #{:component} checks) "component" "brick"))

(defn- std-devs-result
  [{k :std-devs :keys [min-peers] :or {min-peers 3}} {:keys [direction checks]}
   value peer-values]
  (when (>= (count peer-values) (max 2 min-peers))
    (let [m (mean peer-values)
          sd (std-dev peer-values)
          limit (if (= :max direction) (+ m (* k sd)) (- m (* k sd)))
          side (if (= :max direction) "above" "below")]
      (when (past? direction limit value)
        {:limit limit
         :stats {:mean m :std-dev sd :peers (count peer-values)}
         :message (if (zero? sd)
                    (str (fmt value) " is " side " every other "
                      (peer-noun checks) " (" (fmt m) ")")
                    (str (fmt value) " is "
                      (format "%.1f" (Math/abs (/ (- value m) sd)))
                      " standard deviations " side " the mean of "
                      (count peer-values) " other " (peer-noun checks) "s ("
                      (fmt m) " ± " (fmt sd) "), over the limit of "
                      (fmt k)))}))))

(defn- value-result
  "The result of comparing value with threshold t, a number, or nil when it
  isn't past it."
  [direction t value]
  (when (past? direction t value)
    {:limit t
     :message (str (fmt value) " is " (limit-text direction t))}))

(defn- checked?
  [{:keys [checks]} brick]
  (contains? checks (:type brick)))

(defn- base-violation
  [{:keys [key section kind direction]}]
  {:metric key :section section :kind kind :direction direction})

(defmulti ^:private violations
  "Violations of a metric's settings, given the measurements and the
  metric's findings."
  (fn [metric _settings _bricks _findings] (:kind metric)))

(defmethod violations :brick
  [{:keys [key direction] :as metric} settings bricks _]
  (let [checked (filter #(metrics/applies? metric %) bricks)]
    (for [{:keys [brick] :as m} checked
          :let [value (get-in m [:metrics key])]
          :when (some? value)
          :let [peer-values (for [other checked
                                  :when (not= (:name brick)
                                          (get-in other [:brick :name]))
                                  :let [v (get-in other [:metrics key])]
                                  :when (some? v)]
                              v)
                result (first-level settings
                         #(if (map? %)
                            (std-devs-result % metric value peer-values)
                            (value-result direction % value)))]
          :when result]
      (merge (base-violation metric) {:brick brick :value value} result))))

(defmethod violations :function
  [{:keys [direction function-key line-key] :as metric} settings bricks _]
  (for [{:keys [brick functions]} bricks
        :when (checked? metric brick)
        function functions
        :let [value (get function function-key)]
        :when (some? value)
        :let [result (first-level settings #(value-result direction % value))]
        :when result]
    (merge (base-violation metric)
      {:brick brick
       :value value
       :subject (metrics/function-id function)
       :location {:file (:file function)
                  :line (get function (or line-key :line))
                  :name (:name function)}}
      result)))

(defmethod violations :finding
  [{:keys [direction] :as metric} settings _ findings]
  (for [{:keys [brick value message] :as finding} findings
        :when (and (checked? metric brick) (some? value))
        :let [result (first-level settings
                       #(when (past? direction % value)
                          {:limit %
                           :message (str message " ("
                                      (limit-text direction %) ")")}))]
        :when result]
    (merge finding (base-violation metric) result)))

(defmethod violations :count
  [{:keys [direction] :as metric} settings bricks findings]
  (let [by-brick (group-by (comp :name :brick) findings)]
    (for [{:keys [brick]} bricks
          :when (checked? metric brick)
          :let [found (by-brick (:name brick))
                n (count found)
                result (first-level settings
                         #(when (past? direction % n) {:limit %}))]
          :when result
          finding found]
      (merge finding (base-violation metric)
        {:value n}
        result
        (when-not (zero? (:limit result))
          {:message (str (:message finding) " (" n " in this brick, "
                      (limit-text direction (:limit result)) ")")})))))

(defn- on?
  [settings]
  (some #(some? (get settings %)) levels))

(defn- aggregate
  "A :function or :finding metric's value for a brick, from its violations
  and findings: their :count (the default), the :sum of the violations'
  values, or the :value of its one finding, with the finding's subject."
  [{:keys [key aggregate]} violated found]
  (case aggregate
    :sum {key (reduce + 0 (map :value violated))}
    :value (when-let [{:keys [value subject]} (first found)]
             {key value (metrics/subject-key key) subject})
    {key (count violated)}))

(defn- counts
  "Each brick's value of the metrics whose values come from evaluating
  them: for a :function or :finding metric, from its violations (see
  aggregate); for a :count metric, its findings. nil for a brick of a type
  the metric doesn't describe, or a :function or :finding metric that is
  off."
  [config bricks findings violations]
  (let [violated (group-by (juxt :metric (comp :name :brick)) violations)
        found (group-by (juxt first (comp :name :brick second))
                (for [[k fs] findings
                      f fs]
                  [k f]))]
    (for [{:keys [brick] :as m} bricks]
      (update m :metrics merge
        (into {}
          (for [{:keys [key kind types] :as metric}
                (map metrics/metric (map :key metrics/metrics))
                :when (#{:function :finding :count} kind)
                :let [cell [key (:name brick)]]]
            (cond
              (not (contains? types (:type brick))) {key nil}
              (= :count kind) {key (count (found cell))}
              (on? (settings config key)) (aggregate metric (violated cell)
                                            (map second (found cell)))
              :else {key nil})))))))

(defn check
  [config bricks findings]
  (let [found (vec
                (for [{:keys [key]} metrics/metrics
                      :let [metric (metrics/metric key)
                            s (settings config key)]
                      :when (on? s)
                      v (violations metric s bricks (get findings key))]
                  v))]
    {:bricks (vec (counts config bricks findings found))
     :violations found}))

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
