(ns systems.thoughtfull.assay.text-report.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.metrics.interface :as metrics]))

(def ^:private labels
  {:brick (into {} (map (juxt :key :label)) metrics/metrics)
   :function (into {} (map (juxt :key :label)) metrics/function-metrics)})

(defn- label
  [{:keys [scope metric label]}]
  (or label (get-in labels [(or scope :brick) metric]) (name metric)))

(defn- shown?
  [{:keys [status]}]
  (contains? #{nil :new} status))

(defn- line
  [{:keys [brick level message location] :as violation}]
  (str (format "%-8s" (name level))
    (if (:file location)
      (str (:file location) ":" (:line location))
      (:dir brick (:name brick)))
    "  " (label violation) " " message
    (when (:name location) (str " (" (:name location) ")"))))

(defn- plural
  [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn- warnings-text
  "Shown warnings, or the count hidden when warnings were left out."
  [qualifier shown-count hidden-warnings]
  (if hidden-warnings
    (str (plural hidden-warnings (str qualifier "warning"))
      " hidden (--warnings to show)")
    (plural shown-count (str qualifier "warning"))))

(defn- summary
  [{:keys [bricks violations comparison hidden-warnings]}]
  (let [{shown true hidden false} (group-by shown? violations)
        counts (frequencies (map :level shown))
        qualifier (if comparison "new " "")]
    (str "assay: " (plural (count bricks) "brick") ", "
      (plural (counts :error 0) (str qualifier "error")) ", "
      (warnings-text qualifier (counts :warning 0) hidden-warnings)
      (when comparison
        (str ", compared with " (:base-ref comparison)
          (when (seq hidden)
            (str " (" (count hidden) " not new, not shown)")))))))

(defn render
  [report]
  (str/join "\n"
    (concat
      (->> (:violations report)
        (filter shown?)
        (sort-by (juxt #(if (= :error (:level %)) 0 1)
                   #(get-in % [:location :file] "")
                   #(get-in % [:location :line] 0)))
        (map line))
      [(summary report)
       ""])))
