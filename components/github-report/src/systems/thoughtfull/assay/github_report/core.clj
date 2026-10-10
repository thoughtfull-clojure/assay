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
  "Findings that a change did not introduce are notices, so they don't
  read as failures."
  [{:keys [level status]}]
  (if (#{:existing :indirect} status)
    "notice"
    (name level)))

(defn- row-label
  "A row's metric label, or for a function's row, Functions."
  [{:keys [group] :as row}]
  (if (= :function-rows group) "Functions" (metrics/label row)))

(defn- row-locations
  "Where to anchor a row's annotations: each of its locations, or for a
  finding about a whole brick, the first line of the brick's deps.edn."
  [{:keys [bricks locations]}]
  (or (seq locations)
    (when-let [dir (:dir (first bricks))]
      [{:file (str dir "/deps.edn") :line 1}])
    [nil]))

(defn- annotation
  [{:keys [bricks status locations] :as row} location]
  (let [props (cond-> []
                (:file location) (conj (str "file=" (escape-property
                                                      (:file location))))
                (:line location) (conj (str "line=" (:line location)))
                :always (conj (str "title=" (escape-property
                                              (str (brick-label (first bricks))
                                                ": " (row-label row))))))
        text (str (row-label row) " " (thresholds/row-text row)
               (when-let [n (:name (first locations))] (str " (" n ")"))
               (when (#{:existing :indirect} status)
                 (str " [" (name status) "]")))]
    (str "::" (annotation-command row) " " (str/join "," props) "::"
      (escape-data text))))

(defn annotations
  [report]
  (vec (for [row (thresholds/rows (:violations report))
             location (row-locations row)]
         (annotation row location))))

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

(defn- flagged-cells
  "Map of [brick name, metric] to the worst level of the violations that a
  change introduced or, without a comparison, of all violations."
  [violations]
  (reduce
    (fn [acc {:keys [brick metric level]}]
      (update acc [(:name brick) metric] thresholds/worse-level level))
    {}
    (remove (comp #{:existing :indirect} :status) violations)))

(defn- headline
  [{:keys [bricks violations comparison hidden-warnings]}]
  (let [counted (remove (comp #{:existing :indirect} :status)
                  (thresholds/rows violations))
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

(defn- none-text
  "What to say when no violation is listed."
  [comparison hidden-warnings]
  (str (cond
         (and comparison hidden-warnings) "No new errors"
         comparison "No new violations"
         hidden-warnings "No errors"
         :else "No thresholds exceeded")
    "."))

(defn- hidden-existing-text
  [hidden-existing]
  (when (pos? (or hidden-existing 0))
    (str "\n\n" hidden-existing " existing or indirect "
      (if (= 1 hidden-existing) "violation" "violations")
      " not shown, since the change didn't introduce them.")))

(def ^:private shown-violations
  "How many rows of violations to list before collapsing the rest."
  20)

(def ^:private section-markers
  "A colored square for each section, since GitHub strips the styles that
  could color a cell. Red and yellow are left out: they mean error and
  warning."
  {:dependencies "🟦"
   :complexity "🟪"
   :modularity "🟩"
   :io "🟫"
   :errors "🟧"
   :tests "⬜"})

(defn- metric-text
  "A violation's metric, after its section's marker."
  [violation]
  (str (section-markers (metrics/violation-section violation)) " "
    (metrics/label violation)))

(defn- introduced?
  [{:keys [status]}]
  (contains? #{nil :new} status))

(defn- row-detail
  [{:keys [group locations] :as row}]
  (str (when (= :function-rows group)
         (str "`" (:name (first locations)) "`: "))
    (thresholds/row-text row)))

(defn- locations-text
  [locations]
  (str/join "<br>" (for [{:keys [file line]} locations]
                     (str "`" file ":" line "`"))))

(defn- row-table
  "Rows of violations, with a metric column when they are of different
  metrics and a status column when a comparison left existing ones in."
  [rows {:keys [metric? status?]}]
  (table (cond-> ["Level" "Brick"]
           metric? (conj "Metric")
           :always (conj "Detail" "Location")
           status? (conj "Status"))
    (for [{:keys [level bricks locations status section] :as row} rows]
      (cond-> [(level-mark level)
               (str/join ", " (map brick-label (take 1 bricks)))]
        metric? (conj (str (section-markers section) " " (row-label row)))
        :always (conj (row-detail row) (locations-text locations))
        status? (conj (name status))))))

(defn- collapsed-rows
  "Rows, past the first few collapsed."
  [rows opts]
  (let [[shown more] (split-at shown-violations rows)]
    (str (row-table shown opts)
      (when (seq more)
        (str "\n\n<details><summary>" (count more) " more</summary>\n\n"
          (row-table more opts) "\n\n</details>")))))

(defn- metric-rows
  "Rows counted under each metric, at the level of the row's worst
  violation of it."
  [rows]
  (for [row rows
        [metric vs] (group-by :metric (:violations row))]
    [metric (assoc row :level (reduce thresholds/worse-level nil
                                (map :level vs)))]))

(defn- summary-grid
  "A row for each metric with violations a change introduced (or any,
  without a comparison), with the errors, warnings, and with a
  comparison, how many it resolved."
  [rows {:keys [resolved] :as comparison}]
  (let [introduced (group-by first
                     (filter (comp introduced? second) (metric-rows rows)))
        fixed (frequencies (map :metric resolved))
        lines (for [{:keys [key section]} metrics/metrics
                    :let [{:keys [error warning]}
                          (frequencies (map (comp :level second)
                                         (introduced key)))]
                    :when (or error warning (fixed key))]
                (cond-> [(str (section-markers section) " "
                           (metrics/label {:metric key}))
                         (str (or error 0)) (str (or warning 0))]
                  comparison (conj (str (fixed key 0)))))]
    (when (seq lines)
      (table (cond-> ["Metric" "Errors" "Warnings"]
               comparison (conj "Resolved"))
        lines))))

(defn- violations-section
  "The summary grid, and with a comparison, the new violations."
  [{:keys [violations comparison hidden-warnings hidden-existing]}]
  (let [rows (thresholds/rows violations)
        status? (and comparison (nil? hidden-existing))
        new (filter introduced? rows)]
    (str "### Violations\n\n"
      (or (summary-grid rows comparison)
        (none-text comparison hidden-warnings))
      (when (and comparison (seq new))
        (str "\n\n**New violations**\n\n"
          (collapsed-rows new {:metric? true :status? status?})))
      (hidden-existing-text hidden-existing))))

(defn- level-counts
  [rows]
  (let [{:keys [error warning]} (frequencies (map :level rows))]
    (str/join ", " (remove nil?
                     [(when error (str error (if (= 1 error) " error"
                                               " errors")))
                      (when warning (str warning (if (= 1 warning) " warning"
                                                   " warnings")))]))))

(defn- section-violations
  "A section's violations, a collapsed group for each metric (and one for
  functions), in column order. Library spread is in the shared libraries
  table."
  [{:keys [key columns]} {:keys [violations comparison hidden-existing]}]
  (let [status? (and comparison (nil? hidden-existing))
        groups (group-by :group (filter #(= key (:section %))
                                  (thresholds/rows violations)))]
    (str/join "\n\n"
      (for [group (cons :function-rows (remove #{:library-spread}
                                         (mapcat :keys columns)))
            :let [rows (groups group)]
            :when (seq rows)]
        (str "<details><summary>"
          (if (= :function-rows group)
            "Functions"
            (metrics/label {:metric group}))
          ": " (level-counts rows) "</summary>\n\n"
          (collapsed-rows rows {:status? status?}) "\n\n</details>")))))

(defn- resolved-section
  [{:keys [comparison]}]
  (when-let [resolved (seq (:resolved comparison))]
    (str "### Resolved\n\n"
      (table ["Brick" "Metric" "Detail"]
        (for [{:keys [brick message] :as violation} resolved]
          [(brick-label brick) (metric-text violation) message])))))

(defn- metric-cell
  [level text]
  (case level
    :error (str "**" text "** ❌")
    :warning (str "**" text "** ⚠️")
    :outlier (str "**" text "**")
    text))

(defn- legend
  "A collapsed table explaining each column, with its thresholds."
  [columns config]
  (str "<details><summary>What these metrics mean</summary>\n\n"
    (table ["Metric" "Meaning"]
      (for [{:keys [label explanation keys]} columns
            :let [ts (keep #(thresholds/describe config %) keys)]]
        [label (str explanation
                 (when (seq ts)
                   (str " **Threshold:** " (str/join "; " ts) ".")))]))
    "\n\n</details>"))

(defn- changed-keys
  "The keys of a column's metrics that changed from base."
  [{:keys [keys]} base head]
  (when base
    (for [k keys
          :let [b (get base k) h (get head k)]
          :when (and (some? b) (some? h) (not= b h))]
      k)))

(defn- delta-text
  "How much a column's metrics changed from base, such as \" (+3)\"."
  [column base head]
  (str/join
    (for [k (changed-keys column base head)
          :let [b (get base k) h (get head k)]]
      (str " (" (when (> h b) "+") (metrics/format-value k (- h b)) ")"))))

(defn- shown-bricks
  "The bricks a section's table shows: every brick, or with a comparison,
  the bricks new since the base or with a column of the section changed."
  [columns bricks {:keys [changed-bricks base-metrics] :as comparison}]
  (if comparison
    (filter (fn [{:keys [brick metrics]}]
              (let [base (base-metrics (:name brick))]
                (if base
                  (some #(seq (changed-keys % base metrics)) columns)
                  (contains? changed-bricks (:name brick)))))
      bricks)
    bricks))

(defn- unchanged-text
  [noun shown unchanged]
  (str "\n\n"
    (if (empty? shown)
      (str "No " noun " changed in this section. The average is of all "
        unchanged " " noun "s.")
      (str unchanged " unchanged " noun (when (< 1 unchanged) "s")
        " not shown. The average is of all " noun "s."))))

(defn- section-table
  "A section's columns for every brick it shows (components only, for a
  components-only section), then an average row of those bricks, a key
  for bold outliers if there are any, and the legend. With a comparison,
  only bricks that are new or changed in the section are shown, with how
  much each value changed, and a key counts the rest."
  [{:keys [columns components-only] :as section}
   {:keys [violations comparison] :as report}]
  (let [bricks (metrics/section-measurements section (:bricks report))
        noun (if components-only "component" "brick")
        flagged (flagged-cells violations)
        outliers (metrics/outliers bricks metrics/outlier-std-devs)
        outlier? (fn [brick-name {:keys [keys]}]
                   (some #(outliers [brick-name %]) keys))
        column-level (fn [brick-name {:keys [keys] :as column}]
                       (or (reduce thresholds/worse-level nil
                             (map #(flagged [brick-name %]) keys))
                         (when (outlier? brick-name column) :outlier)))
        averages (metrics/averages bricks)
        {:keys [base-metrics]} comparison
        shown (shown-bricks columns bricks comparison)
        unchanged (- (count bricks) (count shown))]
    (str
      (table (cons "Brick" (map :label columns))
        (concat
          (for [{:keys [brick] :as m} shown
                :let [brick-name (:name brick)
                      base (when comparison (base-metrics brick-name))]]
            (cons (str (brick-label brick)
                    (when (and comparison (nil? base)) " (new)"))
              (for [column columns]
                (metric-cell (column-level brick-name column)
                  (str (metrics/column-text column (:metrics m))
                    (delta-text column base (:metrics m)))))))
          [(cons "**Average**"
             (for [column columns]
               (metrics/column-text column averages)))]))
      (when (and comparison (pos? unchanged))
        (unchanged-text noun shown unchanged))
      (when (some (fn [{:keys [brick]}]
                    (some #(outlier? (:name brick) %) columns))
              shown)
        (str "\n\nBold: " metrics/outlier-std-devs " or more standard"
          " deviations worse than the mean of the other bricks the metric"
          " checks. Only ratios, densities, and means are outlined."))
      "\n\n" (legend columns (:thresholds report)))))

(defn- dependencies-graph
  "The brick graph as a Mermaid diagram, which GitHub renders."
  [{:keys [bricks edges violations graph-violations]}]
  (when (seq edges)
    (str "Red: a dependency on a less stable brick. Dashed: new"
      " since the base. Dotted amber, no arrow: bricks that change"
      " together but don't depend on each other.\n\n"
      "```mermaid\n"
      (dependencies/mermaid bricks edges
        (or graph-violations violations))
      "\n```")))

(defn- spread-text
  "How far a library spread across n bricks is past the :library-spread
  settings: a level mark, allowed, or nothing."
  [{:keys [allow] :as settings} library n]
  (if (contains? (set (map str allow)) library)
    "allowed"
    (or (some #(when-some [t (get settings %)]
                 (when (> n t) (level-mark %)))
          [:error :warning])
      "")))

(defn- shared-libraries-table
  "Libraries that more than one brick requires, most spread first, with
  how far each is past the :library-spread limit."
  [{:keys [libraries thresholds]}]
  (when-let [shared (seq (filter #(< 1 (count (:bricks %))) libraries))]
    (let [settings (thresholds/settings thresholds :library-spread)]
      (str "**Shared libraries**\n\n"
        (table ["Library" "Bricks" "Spread" "Required by"]
          (for [{:keys [library bricks]} shared]
            [(str "`" library "`") (str (count bricks))
             (spread-text settings library (count bricks))
             (str/join ", " bricks)]))))))

(defn- metric-sections
  "A section for each group of metrics. Dependencies start with the brick
  graph."
  [report]
  (for [{:keys [key label] :as section} metrics/sections]
    (str "### " (section-markers key) " " label "\n\n"
      (when (= :dependencies key)
        (some-> (dependencies-graph report) (str "\n\n")))
      (section-table section report)
      (when (= :io key)
        (some->> (shared-libraries-table report) (str "\n\n")))
      (some->> (not-empty (section-violations section report))
        (str "\n\n")))))

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
