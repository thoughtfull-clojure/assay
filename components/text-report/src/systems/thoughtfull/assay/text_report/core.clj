(ns systems.thoughtfull.assay.text-report.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.metrics.interface :as metrics]))

(def ^:private labels
  (into {} (map (juxt :key :label)) metrics/metrics))

(defn- shown?
  [{:keys [status]}]
  (contains? #{nil :new} status))

(defn- line
  [{:keys [brick metric level message location]}]
  (str (format "%-8s" (name level))
    (if (:file location)
      (str (:file location) ":" (:line location))
      (:dir brick (:name brick)))
    "  " (labels metric (name metric)) " " message
    (when (:name location) (str " (" (:name location) ")"))))

(defn- plural
  [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn- summary
  [{:keys [bricks violations comparison]}]
  (let [{shown true hidden false} (group-by shown? violations)
        counts (frequencies (map :level shown))
        qualifier (if comparison "new " "")]
    (str "assay: " (plural (count bricks) "brick") ", "
      (counts :error 0) " " qualifier (if (= 1 (counts :error 0)) "error" "errors")
      ", "
      (counts :warning 0) " " qualifier
      (if (= 1 (counts :warning 0)) "warning" "warnings")
      (when comparison
        (str " compared with " (:base-ref comparison)
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
