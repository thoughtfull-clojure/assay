(ns systems.thoughtfull.assay.baseline.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.metrics.interface :as metrics]))

(defn changed-bricks
  [measurements changed-files]
  (into #{}
    (for [{:keys [brick]} measurements
          :let [prefix (str (:dir brick) "/src/")]
          :when (some #(str/starts-with? % prefix) changed-files)]
      (:name brick))))

(defn- violation-key
  "Identifies a violation across reports: a function or finding is
  identified by its :subject, not by its line, which moves."
  [{:keys [brick metric subject]}]
  [(:name brick) metric subject])

(defn- worsened?
  "True if a function violation's value is further past its limit than the
  same function's value in base: a change to the function made it worse."
  [{:keys [kind direction value base-value]}]
  (and (= :function kind) (number? value) (number? base-value)
    (case direction
      :max (> value base-value)
      :min (< value base-value)
      false)))

(defn- status
  "A historical violation, from history rather than code, is never new. A
  function violation that a change made worse is new, even though it was
  in base."
  [base-keys changed violation]
  (cond
    (:historical? violation) :existing
    (worsened? violation) :new
    (base-keys (violation-key violation)) :existing
    (changed (:name (:brick violation))) :new
    :else :indirect))

(defn- base-value
  "The violation's metric in base: the brick's metric, or for a function
  violation, the same function's."
  [base-metrics base-functions {:keys [kind brick metric subject]}]
  (case kind
    :function (get-in base-functions [[(:name brick) subject]
                                      (:function-key (metrics/metric metric))])
    :brick (get-in base-metrics [(:name brick) metric])
    nil))

(defn- fmt
  [x]
  (if (== x (Math/rint x))
    (str (long x))
    (format "%.1f" (double x))))

(defn- compared
  "A head violation with its :base-value and :status, and, when a change
  made a function worse, what its value was. base is a map of :keys (base
  violation keys), :metrics, :functions, and :changed (head's changed
  brick names)."
  [{:keys [keys metrics functions changed]} violation]
  (let [v (assoc violation
            :base-value (base-value metrics functions violation))]
    (cond-> (assoc v :status (status keys changed v))
      (and (worsened? v) (:message v))
      (update :message str " (was " (fmt (:base-value v)) ")"))))

(defn- mark-new-edges
  "Head's edges, each that base doesn't have marked :new?."
  [base head]
  (let [base-edges (set (map (juxt :from :to) (:edges base)))]
    (mapv #(cond-> % (not (base-edges [(:from %) (:to %)])) (assoc :new? true))
      (:edges head))))

(defn compare-reports
  [base head changed-files]
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
        violations (map #(compared {:keys base-keys
                                    :metrics base-metrics
                                    :functions base-functions
                                    :changed changed}
                           %)
                     (:violations head))]
    (assoc head
      :violations (vec violations)
      :edges (mark-new-edges base head)
      :comparison {:changed-bricks changed
                   :base-metrics base-metrics
                   :resolved (vec (remove (comp head-keys violation-key)
                                    (:violations base)))})))
