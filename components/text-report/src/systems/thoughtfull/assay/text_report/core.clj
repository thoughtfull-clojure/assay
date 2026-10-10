(ns systems.thoughtfull.assay.text-report.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.metrics.interface :as metrics]
   [systems.thoughtfull.assay.thresholds.interface :as thresholds]))

(defn- shown?
  [{:keys [status]}]
  (contains? #{nil :new} status))

(defn- where
  "Where a row is: its first location, or its first brick's directory."
  [{:keys [bricks locations]}]
  (if-let [{:keys [file line]} (first locations)]
    (str file ":" line)
    (let [{:keys [dir name]} (first bricks)]
      (or dir name))))

(defn- what
  "What a row says: a function's name and the limits it is past, or a
  metric's label and what its violation says."
  [{:keys [group locations] :as row}]
  (if (= :function-rows group)
    (str (:name (first locations)) ": " (thresholds/row-text row))
    (str (metrics/label row) " " (thresholds/row-text row))))

(defn- line
  [{:keys [level] :as row}]
  (str (format "%-8s" (name level)) (where row) "  " (what row)))

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
  [{:keys [bricks violations comparison hidden-warnings hidden-existing]}]
  (let [{shown true hidden false} (group-by shown?
                                    (thresholds/rows violations))
        not-new (+ (count hidden) (or hidden-existing 0))
        counts (frequencies (map :level shown))
        qualifier (if comparison "new " "")]
    (str "assay: " (plural (count bricks) "brick") ", "
      (plural (counts :error 0) (str qualifier "error")) ", "
      (warnings-text qualifier (counts :warning 0) hidden-warnings)
      (when comparison
        (str ", compared with " (:base-ref comparison)
          (when (pos? not-new)
            (str " (" not-new " not new, not shown)")))))))

(defn- section-lines
  "A heading for each section with rows to show, then a line for each."
  [rows]
  (let [by-section (group-by :section rows)]
    (for [{:keys [key label]} metrics/sections
          :let [section-rows (by-section key)]
          :when (seq section-rows)]
      (str/join "\n" (cons label (map line section-rows))))))

(defn render
  [report]
  (str/join "\n\n"
    (concat
      (section-lines (filter shown? (thresholds/rows (:violations report))))
      [(str (summary report) "\n")])))
