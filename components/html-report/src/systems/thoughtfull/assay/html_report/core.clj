(ns systems.thoughtfull.assay.html-report.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.dependencies.interface :as dependencies]
   [systems.thoughtfull.assay.metrics.interface :as metrics]
   [systems.thoughtfull.assay.thresholds.interface :as thresholds]))

;; A minimal hiccup-style renderer: [:tag {attrs} & children]. Strings are
;; escaped unless wrapped in Raw; seqs are spliced; nil renders nothing.

(deftype Raw [s])

(defn- escape
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- html
  [x]
  (cond
    (nil? x) ""
    (instance? Raw x) (.-s ^Raw x)
    (vector? x) (let [[tag & more] x
                      [attrs children] (if (map? (first more))
                                         [(first more) (rest more)]
                                         [{} more])]
                  (str "<" (name tag)
                    (str/join
                      (for [[k v] attrs :when (some? v)]
                        (str " " (name k) "=\"" (escape v) "\"")))
                    ">"
                    (str/join (map html children))
                    "</" (name tag) ">"))
    (seq? x) (str/join (map html x))
    :else (escape x)))

(def ^:private style
  "
:root {
  --bg: #fbfbfa; --surface: #ffffff; --text: #1c1d1f; --muted: #5f6368;
  --border: #e3e3e0; --error: #b3261e; --error-bg: #fce8e6;
  --warning: #8a5300; --warning-bg: #fef3d6; --ok: #1e6b3a;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #141517; --surface: #1c1d20; --text: #e8e8e6; --muted: #a0a3a8;
    --border: #2e3034; --error: #f2b8b5; --error-bg: #3c1d1b;
    --warning: #f5cf7a; --warning-bg: #3a2c0c; --ok: #8fd4a6;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--bg); color: var(--text);
  font: 15px/1.5 system-ui, -apple-system, 'Segoe UI', sans-serif;
}
main { max-width: 1200px; margin: 0 auto; padding: 32px 16px 64px; }
h1 { font-size: 26px; margin: 0 0 4px; }
h2 { font-size: 18px; margin: 40px 0 12px; }
.meta { color: var(--muted); margin: 0; }
.tiles { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 24px; }
.tile {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 8px; padding: 12px 16px; min-width: 140px;
}
.tile .n { font-size: 28px; font-weight: 600; font-variant-numeric: tabular-nums; }
.tile .l { color: var(--muted); font-size: 13px; }
.tile.error .n { color: var(--error); }
.tile.warning .n { color: var(--warning); }
.tile.ok .n { color: var(--ok); }
.scroll { overflow-x: auto; border: 1px solid var(--border); border-radius: 8px; }
table { border-collapse: collapse; width: 100%; background: var(--surface); }
th, td { padding: 8px 12px; text-align: left; border-bottom: 1px solid var(--border); }
tr:last-child td { border-bottom: none; }
th {
  font-size: 13px; font-weight: 600; color: var(--muted);
  vertical-align: bottom; line-height: 1.3;
}
th.num { min-width: 4.5em; }
td.num { white-space: nowrap; }
td:first-child > code, .brick-name, .type { white-space: nowrap; }
td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
td.error { background: var(--error-bg); color: var(--error); font-weight: 600; }
td.warning { background: var(--warning-bg); color: var(--warning); font-weight: 600; }
.badge {
  display: inline-block; padding: 1px 8px; border-radius: 999px;
  font-size: 12px; font-weight: 600;
}
.badge.error { background: var(--error-bg); color: var(--error); }
.badge.warning { background: var(--warning-bg); color: var(--warning); }
code { font: 13px ui-monospace, SFMono-Regular, Menlo, monospace; }
.type { color: var(--muted); font-size: 13px; }
p.none { color: var(--muted); }
.badge.status { background: var(--bg); color: var(--muted); border: 1px solid var(--border); }
.badge.new { background: var(--error-bg); color: var(--error); }
.up { color: var(--error); }
.down { color: var(--ok); }
tr.total td { font-weight: 600; border-top: 2px solid var(--border); }
details.legend { margin-top: 12px; }
details.dependency-table .scroll { margin-top: 12px; }
pre.mermaid { background: none; margin: 0; text-align: center; }
pre.mermaid:not([data-processed]) { visibility: hidden; height: 0; }
.graph-key { color: var(--muted); font-size: 13px; margin: 0 0 8px; }
details.legend summary { cursor: pointer; color: var(--muted); font-size: 13px; }
details.legend dl {
  display: grid; grid-template-columns: max-content 1fr; gap: 6px 16px;
  margin: 12px 0 0; font-size: 14px;
}
details.legend dt { font-weight: 600; }
details.legend dd { margin: 0; color: var(--muted); }
details.legend dd code {
  color: var(--text); background: var(--bg); border: 1px solid var(--border);
  border-radius: 4px; padding: 0 4px;
}
@media (max-width: 600px) {
  details.legend dl { grid-template-columns: 1fr; }
  details.legend dd { margin-bottom: 8px; }
}
")

(defn- introduced?
  "True for violations that a change introduced, or for every violation
  when there is no comparison."
  [{:keys [status]}]
  (contains? #{nil :new} status))

(defn- location
  [{:keys [file line name]}]
  (when file
    (list [:code (str file ":" line)] (when name (list " " [:code name])))))

(defn- badge
  [class text]
  [:span {:class (str "badge " class)} text])

(defn- level-badge
  [level]
  (badge (name level) (name level)))

(defn- tile
  [class n text]
  [:div {:class (str "tile " class)}
   [:div {:class "n"} n]
   [:div {:class "l"} text]])

(defn- table
  "A scrolling table. Headers are strings or [attrs string]; rows are
  sequences of cells, each content or [:td ...], with an optional :class in
  their metadata."
  [headers rows]
  (let [th #(if (vector? %) [:th (first %) (second %)] [:th %])
        td #(if (and (vector? %) (= :td (first %))) % [:td %])]
    [:div {:class "scroll"}
     [:table
      [:thead [:tr (map th headers)]]
      [:tbody (for [row rows]
                [:tr {:class (:class (meta row))} (map td row)])]]]))

(defn- metric-headers
  [registry]
  (map (fn [{:keys [label description]}]
         [{:class "num" :title description} label])
    registry))

(defn- brick-cell
  "A brick's name and type, each kept on one line."
  [brick]
  (list [:span {:class "brick-name"} (:name brick)] " "
    [:span {:class "type"} (name (:type brick))]))

(def ^:private status-order
  {:new 0 nil 0 :indirect 1 :existing 2})

;; Violations

(defn- violations-table
  [violations comparison hidden-warnings]
  (if (empty? violations)
    [:p {:class "none"}
     (if hidden-warnings "No errors." "No thresholds exceeded.")]
    (table (cond-> ["Level" "Brick" "Metric" "Detail" "Location"]
             comparison (conj "Status"))
      (for [{:keys [brick level message status] :as v}
            (sort-by (juxt (comp status-order :status)
                       #(if (= :error (:level %)) 0 1)
                       (comp :name :brick))
              violations)]
        (cond-> [(level-badge level)
                 (brick-cell brick)
                 (metrics/label v)
                 message
                 (location (:location v))]
          comparison (conj (badge (str "status " (name status))
                             (name status))))))))

(defn- resolved-table
  [resolved]
  (table ["Brick" "Metric" "Detail"]
    (for [{:keys [brick message] :as v} resolved]
      [(brick-cell brick) (metrics/label v) message])))

;; Metrics

(defn- delta-content
  [k base head]
  (let [text (partial metrics/format-value k)]
    (cond
      (nil? head) (text head)
      (nil? base) (list (text head) " " [:span {:class "type"} "new"])
      (= base head) (text head)
      :else (list (text base) " → " (text head) " "
              [:span {:class (if (> head base) "up" "down")}
               "(" (if (> head base) "+" "") (text (- head base)) ")"]))))

(defn- delta-cell
  [{:keys [keys]} base head]
  [:td {:class "num"}
   (interpose " / " (map #(delta-content % (get base %) (get head %)) keys))])

(defn- changes-table
  [bricks {:keys [changed-bricks base-metrics]}]
  (let [changed (filter #(changed-bricks (:name (:brick %))) bricks)]
    (if (empty? changed)
      [:p {:class "none"} "No bricks changed."]
      (table (cons "Brick" (metric-headers metrics/columns))
        (for [{:keys [brick] :as m} changed]
          (cons (brick-cell brick)
            (for [column metrics/columns]
              (delta-cell column (base-metrics (:name brick))
                (:metrics m)))))))))

(defn- flagged-cells
  "Map of a cell key, from cell-key, to the worst :level and the :messages
  of the introduced violations of scope."
  [violations scope cell-key]
  (reduce
    (fn [acc {:keys [level message] :as v}]
      (-> acc
        (update-in [(cell-key v) :level] thresholds/worse-level level)
        (update-in [(cell-key v) :messages] (fnil conj []) message)))
    {}
    (filter #(and (= scope (:scope % :brick)) (introduced? %)) violations)))

(defn- metric-cell
  [{:keys [level messages]} text]
  [:td {:class (str "num" (when level (str " " (name level))))
        :title (when messages (str/join "\n" messages))}
   text])

(defn- column-flags
  "The flags of a column's metrics combined: the worst level and every
  message."
  [flagged brick-name {:keys [keys]}]
  (let [flags (keep #(flagged [brick-name %]) keys)]
    (when (seq flags)
      {:level (reduce thresholds/worse-level nil (map :level flags))
       :messages (vec (mapcat :messages flags))})))

(defn- metrics-table
  "The summary table: every brick, then a total row."
  [bricks violations]
  (let [flagged (flagged-cells violations :brick
                  (juxt (comp :name :brick) :metric))
        totals (metrics/totals bricks)]
    (table (cons "Brick" (metric-headers metrics/columns))
      (concat
        (for [{:keys [brick] :as m} bricks]
          (cons (brick-cell brick)
            (for [column metrics/columns]
              (metric-cell (column-flags flagged (:name brick) column)
                (metrics/column-text column (:metrics m))))))
        [(with-meta
           (cons "Total"
             (for [column metrics/columns]
               (metric-cell nil (metrics/column-text column totals))))
           {:class "total"})]))))

(defn- inline-code
  "Text with `backticked` spans rendered as code."
  [s]
  (map-indexed (fn [i part] (if (odd? i) [:code part] part))
    (str/split s #"`" -1)))

(defn- legend
  "A collapsed list explaining each entry of a metric registry."
  [entries]
  [:details {:class "legend"}
   [:summary "What these metrics mean"]
   [:dl (for [{:keys [label explanation]} entries]
          (list [:dt label] [:dd (inline-code explanation)]))]])

;; Functions

(defn- functions-table
  "Functions that break a rule, then the most complex of the rest."
  [bricks violations n]
  (let [flagged (flagged-cells violations :function
                  (juxt (comp :name :brick) :subject :metric))
        flagged-fns (set (map (comp vec (partial take 2)) (keys flagged)))
        functions (metrics/notable-functions bricks
                    #(flagged-fns [(:name (:brick %)) (metrics/function-id %)]) n)]
    (if (empty? functions)
      [:p {:class "none"} "No functions found."]
      (table (concat ["Function" "Brick"]
               (metric-headers metrics/function-metrics)
               ["Location"])
        (for [{:keys [brick] :as f} functions]
          (concat
            [[:code (:name f)] (:name brick)]
            (for [{k :key} metrics/function-metrics]
              (metric-cell (flagged [(:name brick) (metrics/function-id f) k]) (get f k)))
            [(location (dissoc f :name))]))))))

;; Dependencies

(defn- dependencies-table
  [bricks edges]
  (table ["Brick" "Depends on" "Depended on by"]
    (for [{:keys [brick depends-on depended-on-by]}
          (dependencies/neighbors bricks edges)]
      [(brick-cell brick)
       (str/join ", " depends-on)
       (str/join ", " depended-on-by)])))

(def ^:private mermaid-script
  "Render .mermaid blocks with Mermaid from a CDN, in the page's color
  scheme. If it can't load, open the dependency table instead."
  "
import('https://cdn.jsdelivr.net/npm/mermaid@12.1.0/dist/mermaid.esm.min.mjs')
  .then(async ({default: mermaid}) => {
    const dark = matchMedia('(prefers-color-scheme: dark)').matches;
    mermaid.initialize({startOnLoad: false, theme: dark ? 'dark' : 'default'});
    await mermaid.run({querySelector: 'pre.mermaid'});
  })
  .catch(() => {
    document.querySelectorAll('details.dependency-table')
      .forEach((d) => { d.open = true; });
  });
")

(defn- dependencies-section
  "The brick graph, drawn by Mermaid, with the same information as a
  collapsed table that opens if Mermaid can't load."
  [{:keys [bricks edges violations]}]
  (if (empty? edges)
    [:p {:class "none"} "No dependencies between bricks."]
    (list
      [:p {:class "graph-key"}
       "Red: a dependency on a less stable brick, or a cycle."
       " Dashed: new since the base."]
      [:pre {:class "mermaid"} (dependencies/mermaid bricks edges violations)]
      [:details {:class "legend dependency-table"}
       [:summary "Dependencies as a table"]
       (dependencies-table bricks edges)]
      [:script {:type "module"} (->Raw mermaid-script)])))

;; Thresholds

(defn- rule-text
  "A rule in short notation, such as \"≤ 10\" or \"increase ≤ 50%\"."
  [{:keys [rule value types]}]
  (str
    (case rule
      :max (str "≤ " value)
      :min (str "≥ " value)
      :std-devs (str "≤ mean + " value "σ of other bricks")
      :max-increase (str "increase ≤ " value)
      :max-increase-percent (str "increase ≤ " value "%")
      (pr-str rule))
    (when types
      (str " (" (str/join ", " (map #(str (name %) "s") (sort types))) ")"))))

(defn- threshold-rows
  [applies-to scope thresholds]
  (for [[metric rules] (sort-by key thresholds)
        {:keys [level] :or {level :error} :as rule} rules]
    [applies-to
     (metrics/label {:scope scope :metric metric})
     (rule-text rule)
     (level-badge level)]))

(defn- dependency-rule-text
  "A dependency rule's [metric rule] text. Some rules have settings."
  [rule {:keys [max min-forms]}]
  (case rule
    :stable-dependencies ["Stable dependencies" "only on more stable bricks"]
    :cycles ["Cycles" "none"]
    :new-dependencies ["New dependencies" "none the base didn't have"]
    :unused-interface ["Unused interface" "none"]
    :connascence-of-position
    ["Connascence of position"
     (str "≤ " max " positional parameters in interface functions others call")]
    :duplicate-code
    ["Duplicate code" (str "none of ≥ " min-forms " forms across bricks")]
    [(name rule) ""]))

(defn- dependency-rows
  [rules]
  (for [[rule setting] (sort-by key rules)
        :let [level (if (map? setting) (:level setting) setting)
              [metric text] (dependency-rule-text rule
                              (when (map? setting) setting))]]
    ["Dependencies" metric text (if level (level-badge level) "off")]))

(defn- thresholds-section
  "Every rule in one table."
  [{:keys [thresholds change-thresholds dependency-rules]}]
  (list
    [:h2 "Thresholds"]
    (table ["Applies to" "Metric" "Rule" "Level"]
      (concat
        (threshold-rows "Functions" :function (:function-thresholds thresholds))
        (threshold-rows "Bricks" :brick (:brick-thresholds thresholds))
        (dependency-rows dependency-rules)
        (threshold-rows "Changes (with --base)" :brick change-thresholds)))))

;; Page

(defn- summary-tiles
  [{:keys [bricks violations comparison hidden-warnings]}]
  (let [counts (frequencies (map :level (filter introduced? violations)))
        qualifier (if comparison "new " "")]
    [:div {:class "tiles"}
     (tile "" (count bricks) "bricks")
     (when comparison
       (tile "" (count (:changed-bricks comparison)) "changed bricks"))
     (tile (if (pos? (counts :error 0)) "error" "ok")
       (counts :error 0) (str qualifier "errors"))
     (if hidden-warnings
       (tile "" hidden-warnings
         (str qualifier "warnings hidden (--warnings to show)"))
       (tile (if (pos? (counts :warning 0)) "warning" "ok")
         (counts :warning 0) (str qualifier "warnings")))
     (when comparison
       (tile "ok" (count (:resolved comparison)) "resolved"))]))

(defn- meta-line
  [{:keys [generated-at comparison]}]
  [:p {:class "meta"}
   "Generated " generated-at
   (when-let [{:keys [base-ref base-rev]} comparison]
     (list " · compared with " [:code base-ref] " (merge-base "
       [:code (subs base-rev 0 (min 12 (count base-rev)))] ")"))])

(defn- comparison-sections
  [{:keys [bricks comparison]}]
  (when comparison
    (list
      (when (seq (:resolved comparison))
        (list [:h2 "Resolved"] (resolved-table (:resolved comparison))))
      [:h2 "Changed bricks"]
      (changes-table bricks comparison))))

(defn- sections
  [{:keys [bricks violations comparison] :as report}]
  (list
    (summary-tiles report)
    [:h2 "Violations"]
    (violations-table violations comparison (:hidden-warnings report))
    (comparison-sections report)
    [:h2 "Bricks"]
    (metrics-table bricks violations)
    (legend metrics/columns)
    [:h2 "Functions"]
    (functions-table bricks violations 15)
    (legend metrics/function-metrics)
    [:h2 "Dependencies"]
    (dependencies-section report)
    (thresholds-section report)))

(defn render
  [report]
  (let [title (str "Assay: " (:workspace report))]
    (str "<!doctype html>\n"
      (html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport"
                  :content "width=device-width, initial-scale=1"}]
          [:title title]
          [:style (->Raw style)]]
         [:body
          [:main
           [:h1 title]
           (meta-line report)
           (sections report)]]])
      "\n")))
