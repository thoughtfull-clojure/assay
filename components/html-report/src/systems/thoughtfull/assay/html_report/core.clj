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
  --outlier: #5b3cc4;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #141517; --surface: #1c1d20; --text: #e8e8e6; --muted: #a0a3a8;
    --border: #2e3034; --error: #f2b8b5; --error-bg: #3c1d1b;
    --warning: #f5cf7a; --warning-bg: #3a2c0c; --ok: #8fd4a6;
    --outlier: #b9a6f5;
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
h3 { font-size: 15px; margin: 24px 0 8px; }
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
td.outlier { box-shadow: inset 0 0 0 2px var(--outlier); font-weight: 600; }
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
tr.average td { font-weight: 600; border-top: 2px solid var(--border); }
details.legend { margin-top: 12px; }
details.dependency-table .scroll,
details.more-violations .scroll { margin-top: 12px; }
pre.mermaid { background: none; margin: 0; text-align: center; }
pre.mermaid:not([data-processed]) { visibility: hidden; height: 0; }
.graph { position: relative; }
.graph.ready {
  height: 70vh; min-height: 320px; resize: vertical; overflow: hidden;
  border: 1px solid var(--border); border-radius: 8px;
  background: var(--surface);
}
.graph.ready:fullscreen { height: 100vh; border-radius: 0; }
.graph.ready pre.mermaid { height: 100%; }
.graph.ready pre.mermaid svg { display: block; cursor: grab; touch-action: none; }
.graph.dragging pre.mermaid svg { cursor: grabbing; }
.graph-controls {
  position: absolute; top: 8px; right: 8px; z-index: 1;
  display: flex; gap: 4px;
}
.graph:not(.ready) .graph-controls { display: none; }
.graph-controls button {
  font: inherit; font-size: 15px; min-width: 32px; height: 32px;
  padding: 0 8px; cursor: pointer; color: var(--text);
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 6px;
}
.graph-controls button:hover { background: var(--bg); }
.graph-controls svg { display: block; margin: auto; }
.graph-key, .table-key { color: var(--muted); font-size: 13px; margin: 0 0 8px; }
.table-key { margin: 8px 0 0; }
.outlier-key {
  display: inline-block; width: 1em; height: 1em; vertical-align: -2px;
  border-radius: 2px; box-shadow: inset 0 0 0 2px var(--outlier);
}
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
  "A brick's name and type, each kept on one line, and an optional tag."
  ([brick] (brick-cell brick nil))
  ([brick tag]
   (list [:span {:class "brick-name"} (:name brick)] " "
     [:span {:class "type"} (name (:type brick))]
     (when tag (list " " (badge "status" tag))))))

(def ^:private status-order
  {:new 0 nil 0 :indirect 1 :existing 2})

;; Violations

(defn- none-text
  "What to say when no violation is listed."
  [comparison hidden-warnings]
  (str (cond
         (and comparison hidden-warnings) "No new errors"
         comparison "No new violations"
         hidden-warnings "No errors"
         :else "No thresholds exceeded")
    "."))

(defn- hidden-existing-key
  [hidden-existing]
  (when (pos? (or hidden-existing 0))
    [:p {:class "table-key"}
     hidden-existing " existing or indirect "
     (if (= 1 hidden-existing) "violation" "violations")
     " not shown, since the change didn't introduce them."]))

(def ^:private shown-violations
  "How many violations to list before collapsing the rest."
  20)

(defn- by-severity
  "Violations a change introduced first, each group most severe first."
  [violations]
  (sort-by (comp status-order :status) (thresholds/by-severity violations)))

(defn- violation-rows
  [violations status?]
  (table (cond-> ["Level" "Brick" "Metric" "Detail" "Location"]
           status? (conj "Status"))
    (for [{:keys [brick level message status] :as v} violations]
      (cond-> [(level-badge level)
               (brick-cell brick)
               (metrics/label v)
               message
               (location (:location v))]
        status? (conj (badge (str "status " (name status))
                        (name status)))))))

(defn- more-violations
  "The violations past the first few, collapsed."
  [more status?]
  (when (seq more)
    [:details {:class "legend more-violations"}
     [:summary (count more) " more "
      (if (= 1 (count more)) "violation" "violations")]
     (violation-rows more status?)]))

(defn- violations-table
  "The violations, most severe first, with a status column when a
  comparison left existing ones in. Past the first few, the rest collapse."
  [{:keys [violations comparison hidden-warnings hidden-existing]}]
  (let [status? (and comparison (nil? hidden-existing))
        [shown more] (split-at shown-violations (by-severity violations))]
    (list
      (if (empty? violations)
        [:p {:class "none"} (none-text comparison hidden-warnings)]
        (list (violation-rows shown status?) (more-violations more status?)))
      (hidden-existing-key hidden-existing))))

(defn- resolved-table
  [resolved]
  (table ["Brick" "Metric" "Detail"]
    (for [{:keys [brick message] :as v} resolved]
      [(brick-cell brick) (metrics/label v) message])))

;; Metrics

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
  [{:keys [level messages outlier?]} text]
  [:td {:class (str "num" (when level (str " " (name level)))
                 (when outlier? " outlier"))
        :title (when messages (str/join "\n" messages))}
   text])

(defn- outlier-message
  [metric {:keys [z mean std-dev peers]}]
  (str (format "%.1f" (abs z)) " standard deviations "
    (if (pos? z) "above" "below") " the mean of all " (name peers) " ("
    (metrics/format-value metric mean) " ± "
    (metrics/format-value metric std-dev) ")"))

(defn- outlier-flags
  "A column's flags with any outliers among its metrics added."
  [flags outliers brick-name {:keys [keys]}]
  (let [found (keep (fn [k]
                      (when-let [o (outliers [brick-name k])]
                        (outlier-message k o)))
                keys)]
    (if (seq found)
      (-> flags
        (assoc :outlier? true)
        (update :messages (fnil into []) found))
      flags)))

(defn- column-flags
  "The flags of a column's metrics combined: the worst level and every
  message."
  [flagged brick-name {:keys [keys]}]
  (let [flags (keep #(flagged [brick-name %]) keys)]
    (when (seq flags)
      {:level (reduce thresholds/worse-level nil (map :level flags))
       :messages (vec (mapcat :messages flags))})))

(defn- deltas
  "[k base head] for each of a column's metrics that changed from base."
  [{:keys [keys]} base head]
  (when base
    (for [k keys
          :let [b (get base k) h (get head k)]
          :when (and (some? b) (some? h) (not= b h))]
      [k b h])))

(defn- delta-span
  [[k base head]]
  (list " " [:span {:class (if (> head base) "up" "down")}
             "(" (when (> head base) "+") (metrics/format-value k (- head base))
             ")"]))

(defn- change-flags
  "A column's flags with what each changed metric was in the base."
  [flags changes]
  (if (seq changes)
    (update flags :messages (fnil into [])
      (for [[k base] changes]
        (str "was " (metrics/format-value k base))))
    flags))

(defn- shown-bricks
  "The bricks a section's table shows: every brick, or with a comparison,
  the bricks new since the base or with a column of the section changed."
  [columns bricks {:keys [changed-bricks base-metrics] :as comparison}]
  (if comparison
    (filter (fn [{:keys [brick metrics]}]
              (let [base (base-metrics (:name brick))]
                (if base
                  (some #(seq (deltas % base metrics)) columns)
                  (contains? changed-bricks (:name brick)))))
      bricks)
    bricks))

(defn- section-table
  "A section's columns for every brick it shows (components only, for a
  components-only section), then an average row of those bricks. With a
  comparison, only bricks that are new or changed in the section are
  shown, with how much each value changed, and a key counts the rest. A
  key explains any outlined value."
  [{:keys [columns components-only] :as section} all-bricks violations
   comparison]
  (let [bricks (metrics/section-measurements section all-bricks)
        noun (if components-only "component" "brick")
        flagged (flagged-cells violations :brick
                  (juxt (comp :name :brick) :metric))
        outliers (metrics/outliers bricks metrics/outlier-std-devs)
        averages (metrics/averages bricks)
        {:keys [base-metrics]} comparison
        shown (shown-bricks columns bricks comparison)
        unchanged (- (count bricks) (count shown))
        outlined? (some (fn [{:keys [brick]}]
                          (some (fn [{:keys [keys]}]
                                  (some #(outliers [(:name brick) %]) keys))
                            columns))
                    shown)]
    (list
      (table (cons "Brick" (metric-headers columns))
        (concat
          (for [{:keys [brick] :as m} shown
                :let [brick-name (:name brick)
                      base (when comparison (base-metrics brick-name))]]
            (cons (brick-cell brick (when (and comparison (nil? base)) "new"))
              (for [column columns
                    :let [changes (deltas column base (:metrics m))]]
                (metric-cell (-> (column-flags flagged brick-name column)
                               (outlier-flags outliers brick-name column)
                               (change-flags changes))
                  (list (metrics/column-text column (:metrics m))
                    (map delta-span changes))))))
          [(with-meta
             (cons "Average"
               (for [column columns]
                 (metric-cell nil (metrics/column-text column averages))))
             {:class "average"})]))
      (when (and comparison (pos? unchanged))
        [:p {:class "table-key"}
         (if (empty? shown)
           (str "No " noun " changed in this section. The average is of"
             " all " unchanged " " noun "s.")
           (str unchanged " unchanged " noun (when (< 1 unchanged) "s")
             " not shown. The average is of all " noun "s."))])
      (when outlined?
        [:p {:class "table-key"}
         [:span {:class "outlier-key"}] " Outlined: "
         metrics/outlier-std-devs " or more standard deviations from the"
         " mean of all bricks (of all components, for metrics that only"
         " describe components)."]))))

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
  scheme, then make each graph pannable and zoomable. If Mermaid can't
  load, open the dependency table instead. Mermaid 11
  is pinned because it draws like GitHub's renderer, so the HTML and
  GitHub reports match; Mermaid 12 lays graphs out differently."
  "
import('https://cdn.jsdelivr.net/npm/mermaid@11.17.2/dist/mermaid.esm.min.mjs')
  .then(async ({default: mermaid}) => {
    const dark = matchMedia('(prefers-color-scheme: dark)').matches;
    mermaid.initialize({startOnLoad: false, theme: dark ? 'dark' : 'default'});
    await mermaid.run({querySelector: 'pre.mermaid'});
    document.querySelectorAll('.graph').forEach(viewer);
  })
  .catch(() => {
    document.querySelectorAll('details.dependency-table')
      .forEach((d) => { d.open = true; });
  });

// Pan and zoom a rendered graph by changing its SVG's viewBox: drag to
// pan, Ctrl or Cmd and the wheel (or a trackpad pinch) to zoom around the
// pointer, and buttons to zoom, fit, and go full screen.
function viewer(graph) {
  const svg = graph.querySelector('pre.mermaid svg');
  if (!svg) return;
  const box = svg.viewBox.baseVal;
  const home = {x: box.x, y: box.y, w: box.width, h: box.height};
  let view = {...home};
  svg.removeAttribute('style');
  svg.setAttribute('width', '100%');
  svg.setAttribute('height', '100%');
  const show = () =>
    svg.setAttribute('viewBox', `${view.x} ${view.y} ${view.w} ${view.h}`);
  // SVG units per screen pixel, as preserveAspectRatio's meet scales.
  const scale = () => Math.max(view.w / svg.clientWidth,
                               view.h / svg.clientHeight);
  const zoom = (f, px, py) => {
    const r = svg.getBoundingClientRect();
    px = px ?? r.width / 2;
    py = py ?? r.height / 2;
    const s = scale();
    const sx = view.x - (r.width * s - view.w) / 2 + px * s;
    const sy = view.y - (r.height * s - view.h) / 2 + py * s;
    view = {x: sx - (sx - view.x) * f, y: sy - (sy - view.y) * f,
            w: view.w * f, h: view.h * f};
    show();
  };
  const actions = {
    'zoom-in': () => zoom(1 / 1.25),
    'zoom-out': () => zoom(1.25),
    'fit': () => { view = {...home}; show(); },
    'fullscreen': () => document.fullscreenElement
      ? document.exitFullscreen() : graph.requestFullscreen(),
  };
  graph.querySelectorAll('[data-action]').forEach((b) =>
    b.addEventListener('click', () => actions[b.dataset.action]()));
  svg.addEventListener('wheel', (e) => {
    if (!(e.ctrlKey || e.metaKey)) return;
    e.preventDefault();
    const r = svg.getBoundingClientRect();
    zoom(Math.exp(e.deltaY * 0.002), e.clientX - r.left, e.clientY - r.top);
  }, {passive: false});
  let last = null;
  svg.addEventListener('pointerdown', (e) => {
    last = {x: e.clientX, y: e.clientY};
    svg.setPointerCapture(e.pointerId);
    graph.classList.add('dragging');
  });
  svg.addEventListener('pointermove', (e) => {
    if (!last) return;
    const s = scale();
    view.x -= (e.clientX - last.x) * s;
    view.y -= (e.clientY - last.y) * s;
    last = {x: e.clientX, y: e.clientY};
    show();
  });
  const stop = () => { last = null; graph.classList.remove('dragging'); };
  svg.addEventListener('pointerup', stop);
  svg.addEventListener('pointercancel', stop);
  graph.classList.add('ready');
}
")

(def ^:private fullscreen-icon
  "Four corners pointing out, drawn in SVG rather than a font glyph that
  some systems lack."
  [:svg {:viewBox "0 0 16 16" :width "14" :height "14" :fill "none"
         :stroke "currentColor" :stroke-width "1.75"
         :stroke-linecap "round" :aria-hidden "true"}
   [:path {:d "M2 6V2h4M10 2h4v4M14 10v4h-4M6 14H2v-4"}]])

(defn- dependencies-section
  "The brick graph, drawn by Mermaid, with the same information as a
  collapsed table that opens if Mermaid can't load."
  [{:keys [bricks edges violations graph-violations]}]
  (when (seq edges)
    (list
      [:p {:class "graph-key"}
       "Red: a dependency on a less stable brick."
       " Dashed: new since the base."
       " Dotted amber, no arrow: bricks that change together but don't"
       " depend on each other. Drag to pan; hold Ctrl or ⌘ and scroll, or"
       " pinch, to zoom."]
      [:div {:class "graph"}
       [:div {:class "graph-controls"}
        (for [[action label title] [["zoom-in" "+" "Zoom in"]
                                    ["zoom-out" "−" "Zoom out"]
                                    ["fit" "Fit" "Fit the whole graph"]
                                    ["fullscreen" fullscreen-icon
                                     "Full screen"]]]
          [:button {:type "button" :data-action action :title title
                    :aria-label title}
           label])]
       [:pre {:class "mermaid"}
        (dependencies/mermaid bricks edges (or graph-violations violations))]]
      [:details {:class "legend dependency-table"}
       [:summary "Dependencies as a table"]
       (dependencies-table bricks edges)]
      [:script {:type "module"} (->Raw mermaid-script)])))

(defn- shared-libraries-table
  "Libraries that more than one brick requires, most spread first."
  [{:keys [libraries]}]
  (when-let [shared (seq (filter #(< 1 (count (:bricks %))) libraries))]
    (list
      [:h3 "Shared libraries"]
      (table ["Library" [{:class "num"} "Bricks"] "Required by"]
        (for [{:keys [library bricks]} shared]
          [[:code library]
           [:td {:class "num"} (count bricks)]
           (str/join ", " bricks)])))))

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

(defn- percent
  [x]
  (Math/round (* 100 (double x))))

(def ^:private dependency-rule-texts
  "Each dependency rule's [metric rule] text, from its settings."
  {:stable-dependencies (constantly ["Stable dependencies"
                                     "only on more stable bricks"])
   :new-dependencies (constantly ["New dependencies"
                                  "none the base didn't have"])
   :mutable-state (constantly ["Mutable state" "none in components"])
   :broad-catch (constantly ["Broad catch" "none in components"])
   :test-boundary (constantly ["Test boundary"
                               "tests use other bricks' interfaces only"])
   :connascence-of-position
   (fn [{:keys [max]}]
     ["Connascence of position"
      (str "≤ " max " positional parameters in interface functions others"
        " call")])
   :duplicate-code
   (fn [{:keys [min-forms]}]
     ["Duplicate code" (str "none of ≥ " min-forms " forms across bricks")])
   :co-change
   (fn [{:keys [since min-shared min-strength]}]
     ["Co-change"
      (str "no bricks without a dependency that changed together in ≥ "
        min-shared " commits and ≥ " (percent min-strength)
        "% of one's commits, over " since)])
   :library-spread
   (fn [{:keys [max-bricks]}]
     ["Library spread"
      (str "each library required by ≤ " max-bricks
        (if (= 1 max-bricks) " brick" " bricks"))])
   :merge-candidates
   (fn [{:keys [max-size]}]
     ["Merge candidates"
      (str "no component with one component dependent, ≤ " (percent max-size)
        "% of its size")])})

(defn- dependency-rule-text
  "A dependency rule's [metric rule] text. Some rules have settings."
  [rule setting]
  (if-let [text (dependency-rule-texts rule)]
    (text setting)
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

(defn- resolved-section
  [{:keys [comparison]}]
  (when (seq (:resolved comparison))
    (list [:h2 "Resolved"] (resolved-table (:resolved comparison)))))

(defn- sections
  [{:keys [bricks violations comparison] :as report}]
  (list
    (summary-tiles report)
    [:h2 "Violations"]
    (violations-table report)
    (resolved-section report)
    (for [{:keys [key label columns] :as section} metrics/sections]
      (list
        [:h2 label]
        (when (= :dependencies key)
          (dependencies-section report))
        (section-table section bricks violations comparison)
        (when (= :io key)
          (shared-libraries-table report))
        (legend columns)))
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
