(ns systems.thoughtfull.assay.github-report.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.dependencies.interface :as dependencies]
   [systems.thoughtfull.assay.metrics.interface :as metrics]
   [systems.thoughtfull.assay.thresholds.interface :as thresholds]))

(defn- brick-label
  [{:keys [type name]}]
  (str (clojure.core/name type) " " name))

;; Annotations

(defn- escape-data
  [s]
  (-> (str s)
    (str/replace "%" "%25")
    (str/replace "\r" "%0D")
    (str/replace "\n" "%0A")))

(defn- escape-property
  [s]
  (-> (escape-data s)
    (str/replace ":" "%3A")
    (str/replace "," "%2C")))

(defn- annotation-command
  "Violations that a change did not introduce are notices, so they don't
  read as failures."
  [{:keys [level status]}]
  (if (#{:existing :indirect} status)
    "notice"
    (name level)))

(defn- annotation-location
  "Where to anchor a violation: its own location, or for a violation of a
  whole brick, the first line of the brick's deps.edn."
  [{:keys [brick location]}]
  (cond
    (:file location) location
    (:dir brick) {:file (str (:dir brick) "/deps.edn") :line 1}))

(defn- annotation
  [{:keys [brick message status] :as violation}]
  (let [location (annotation-location violation)
        props (cond-> []
                (:file location) (conj (str "file=" (escape-property
                                                      (:file location))))
                (:line location) (conj (str "line=" (:line location)))
                :always (conj (str "title=" (escape-property
                                              (str (brick-label brick) ": "
                                                (metrics/label violation))))))
        text (str (metrics/label violation) " " message
               (when-let [n (:name (:location violation))] (str " (" n ")"))
               (when (#{:existing :indirect} status)
                 (str " [" (name status) "]")))]
    (str "::" (annotation-command violation) " " (str/join "," props) "::"
      (escape-data text))))

(defn annotations
  [report]
  (mapv annotation (:violations report)))

;; Summary

(defn- cell
  [x]
  (-> (str x)
    (str/replace "|" "\\|")
    (str/replace #"\r?\n" "<br>")))

(defn- table
  [headers rows]
  (str/join "\n"
    (concat
      [(str "| " (str/join " | " (map cell headers)) " |")
       (str "|" (str/join "|" (repeat (count headers) " --- ")) "|")]
      (for [row rows]
        (str "| " (str/join " | " (map cell row)) " |")))))

(defn- level-mark
  [level]
  (case level
    :error "❌ error"
    :warning "⚠️ warning"
    (name level)))

(defn- location-text
  [{:keys [file line name]}]
  (when file
    (str " (`" file ":" line "`" (when name (str " " name)) ")")))

(defn- flagged-cells
  "Map of [brick name, metric] to the worst level of brick violations that
  a change introduced or, without a comparison, of all brick violations."
  [violations]
  (reduce
    (fn [acc {:keys [brick metric level]}]
      (update acc [(:name brick) metric] thresholds/worse-level level))
    {}
    (->> violations
      (filter #(= :brick (:scope % :brick)))
      (remove (comp #{:existing :indirect} :status)))))

(defn- headline
  [{:keys [bricks violations comparison hidden-warnings]}]
  (let [counted (remove (comp #{:existing :indirect} :status) violations)
        counts (frequencies (map :level counted))
        qualifier (if comparison "new " "")
        base-rev (:base-rev comparison)]
    (str (count bricks) " bricks, "
      (counts :error 0) " " qualifier "errors, "
      (if hidden-warnings
        (str hidden-warnings " " qualifier "warnings hidden (`--warnings` to"
          " show).")
        (str (counts :warning 0) " " qualifier "warnings."))
      (when comparison
        (str " Compared with `" (:base-ref comparison) "` (merge-base `"
          (subs base-rev 0 (min 12 (count base-rev)))
          "`), " (count (:changed-bricks comparison)) " bricks changed.")))))

(def ^:private status-order
  {:new 0 nil 0 :indirect 1 :existing 2})

(defn- violations-section
  [{:keys [violations comparison hidden-warnings]}]
  (str "### Violations\n\n"
    (if (empty? violations)
      (if hidden-warnings "No errors." "No thresholds exceeded.")
      (table (cond-> ["Level" "Brick" "Metric" "Detail"]
               comparison (conj "Status"))
        (for [{:keys [brick level message location status] :as violation}
              (sort-by (comp status-order :status) violations)]
          (cond-> [(level-mark level)
                   (brick-label brick)
                   (metrics/label violation)
                   (str message (location-text location))]
            comparison (conj (name status))))))))

(defn- resolved-section
  [{:keys [comparison]}]
  (when-let [resolved (seq (:resolved comparison))]
    (str "### Resolved\n\n"
      (table ["Brick" "Metric" "Detail"]
        (for [{:keys [brick message] :as violation} resolved]
          [(brick-label brick) (metrics/label violation) message])))))

(defn- metric-cell
  [level text]
  (case level
    :error (str "**" text "** ❌")
    :warning (str "**" text "** ⚠️")
    :outlier (str "**" text "**")
    text))

(defn- legend
  "A collapsed table explaining each entry of a metric registry."
  [entries]
  (str "<details><summary>What these metrics mean</summary>\n\n"
    (table ["Metric" "Meaning"] (map (juxt :label :explanation) entries))
    "\n\n</details>"))

(defn- delta-text
  "How much a column's metrics changed from base, such as \" (+3)\"."
  [{:keys [keys]} base head]
  (when base
    (str/join
      (for [k keys
            :let [b (get base k) h (get head k)]
            :when (and (some? b) (some? h) (not= b h))]
        (str " (" (when (> h b) "+") (metrics/format-value k (- h b)) ")")))))

(defn- section-table
  "A section's columns for every brick, then an average row, a key for
  bold outliers if there are any, and the legend. With a comparison, a
  changed brick's values show how much they changed."
  [columns {:keys [bricks violations comparison]}]
  (let [flagged (flagged-cells violations)
        outliers (metrics/outliers bricks metrics/outlier-std-devs)
        outlier? (fn [brick-name {:keys [keys]}]
                   (some #(outliers [brick-name %]) keys))
        column-level (fn [brick-name {:keys [keys] :as column}]
                       (or (reduce thresholds/worse-level nil
                             (map #(flagged [brick-name %]) keys))
                         (when (outlier? brick-name column) :outlier)))
        averages (metrics/averages bricks)
        {:keys [changed-bricks base-metrics]} comparison]
    (str
      (table (cons "Brick" (map :label columns))
        (concat
          (for [{:keys [brick] :as m} bricks
                :let [brick-name (:name brick)
                      changed? (contains? changed-bricks brick-name)
                      base (when changed? (base-metrics brick-name))]]
            (cons (str (brick-label brick)
                    (when (and changed? (nil? base)) " (new)"))
              (for [column columns]
                (metric-cell (column-level brick-name column)
                  (str (metrics/column-text column (:metrics m))
                    (delta-text column base (:metrics m)))))))
          [(cons "**Average**"
             (for [column columns]
               (metrics/column-text column averages)))]))
      (when (some (fn [{:keys [brick]}]
                    (some #(outlier? (:name brick) %) columns))
              bricks)
        (str "\n\nBold: " metrics/outlier-std-devs " or more standard"
          " deviations from the mean of all bricks (of all components, for"
          " metrics that only describe components)."))
      "\n\n" (legend columns))))

(defn- dependencies-graph
  "The brick graph as a Mermaid diagram, which GitHub renders."
  [{:keys [bricks edges violations]}]
  (when (seq edges)
    (str "Red: a dependency on a less stable brick. Dashed: new"
      " since the base.\n\n"
      "```mermaid\n"
      (dependencies/mermaid bricks edges violations)
      "\n```")))

(defn- metric-sections
  "A section for each group of metrics. Dependencies start with the brick
  graph."
  [report]
  (for [{:keys [key label columns]} metrics/sections]
    (str "### " label "\n\n"
      (when (= :dependencies key)
        (some-> (dependencies-graph report) (str "\n\n")))
      (section-table columns report))))

(defn summary
  [report]
  (str (str/join "\n\n"
         (remove nil?
           [(str "## Assay: " (:workspace report))
            (headline report)
            (violations-section report)
            (resolved-section report)
            (str/join "\n\n" (metric-sections report))]))
    "\n"))
