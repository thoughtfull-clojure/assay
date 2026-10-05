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
  [{:keys [violations comparison]}]
  (when (seq violations)
    (str "### Violations\n\n"
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

(defn- delta-text
  [k base head]
  (let [text (partial metrics/format-value k)]
    (cond
      (nil? head) (text head)
      (nil? base) (str (text head) " (new)")
      (= base head) (text head)
      :else (str (text base) " → " (text head) " ("
              (if (> head base) "+" "") (text (- head base)) ")"))))

(defn- column-delta
  [{:keys [keys]} base head]
  (str/join " / " (map #(delta-text % (get base %) (get head %)) keys)))

(defn- changes-section
  [{:keys [bricks comparison]}]
  (when-let [changed (seq (filter #((:changed-bricks comparison)
                                    (:name (:brick %)))
                            bricks))]
    (str "### Changed bricks\n\n"
      (table (cons "Brick" (map :label metrics/columns))
        (for [{:keys [brick] :as m} changed
              :let [base (get-in comparison [:base-metrics (:name brick)])]]
          (cons (brick-label brick)
            (for [column metrics/columns]
              (column-delta column base (:metrics m)))))))))

(defn- metric-cell
  [level text]
  (case level
    :error (str "**" text "** ❌")
    :warning (str "**" text "** ⚠️")
    text))

(defn- legend
  "A collapsed table explaining each entry of a metric registry."
  [entries]
  (str "<details><summary>What these metrics mean</summary>\n\n"
    (table ["Metric" "Meaning"] (map (juxt :label :explanation) entries))
    "\n\n</details>"))

(defn- bricks-section
  "Every brick, then a total row, and the legend."
  [{:keys [bricks violations]}]
  (let [flagged (flagged-cells violations)
        column-level (fn [brick-name {:keys [keys]}]
                       (reduce thresholds/worse-level nil
                         (map #(flagged [brick-name %]) keys)))
        totals (metrics/totals bricks)]
    (str "### Bricks\n\n"
      (table (cons "Brick" (map :label metrics/columns))
        (concat
          (for [{:keys [brick] :as m} bricks]
            (cons (brick-label brick)
              (for [column metrics/columns]
                (metric-cell (column-level (:name brick) column)
                  (metrics/column-text column (:metrics m))))))
          [(cons "**Total**"
             (for [column metrics/columns]
               (metrics/column-text column totals)))]))
      "\n\n" (legend metrics/columns))))

(defn- flagged-functions
  "Map of [brick name, function name, metric] to the worst level of the
  function violations a change introduced (or all, without a comparison)."
  [violations]
  (reduce
    (fn [acc {:keys [brick subject metric level]}]
      (update acc [(:name brick) subject metric] thresholds/worse-level level))
    {}
    (->> violations
      (filter #(= :function (:scope %)))
      (remove (comp #{:existing :indirect} :status)))))

(defn- functions-section
  "Functions that break a rule, then the most complex of the rest, and the
  legend."
  [{:keys [bricks violations]} n]
  (let [flagged (flagged-functions violations)
        flagged-fns (set (map (comp vec (partial take 2)) (keys flagged)))
        functions (metrics/notable-functions bricks
                    #(flagged-fns [(:name (:brick %)) (metrics/function-id %)]) n)]
    (when (seq functions)
      (str "### Functions\n\n"
        (table (concat ["Function" "Brick"]
                 (map :label metrics/function-metrics)
                 ["Location"])
          (for [{:keys [brick file line] :as f} functions]
            (concat
              [(str "`" (:name f) "`") (:name brick)]
              (for [{k :key} metrics/function-metrics]
                (metric-cell (flagged [(:name brick) (metrics/function-id f) k])
                  (str (get f k))))
              [(str "`" file ":" line "`")])))
        "\n\n" (legend metrics/function-metrics)))))

(defn- dependencies-section
  "The brick graph as a Mermaid diagram, which GitHub renders."
  [{:keys [bricks edges violations]}]
  (when (seq edges)
    (str "### Dependencies\n\n"
      "Red: a dependency on a less stable brick, or a cycle. Dashed: new"
      " since the base.\n\n"
      "```mermaid\n"
      (dependencies/mermaid bricks edges violations)
      "\n```")))

(defn summary
  [report]
  (str (str/join "\n\n"
         (remove nil?
           [(str "## Assay: " (:workspace report))
            (headline report)
            (violations-section report)
            (resolved-section report)
            (when (:comparison report) (changes-section report))
            (functions-section report 15)
            (dependencies-section report)
            (bricks-section report)]))
    "\n"))
