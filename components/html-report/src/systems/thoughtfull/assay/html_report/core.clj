(ns systems.thoughtfull.assay.html-report.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.metrics.interface :as metrics]))

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
th { font-size: 13px; font-weight: 600; color: var(--muted); white-space: nowrap; }
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
")

(defn- worse
  [a b]
  (if (some #{:error} [a b]) :error (or a b)))

(def ^:private labels
  (into {} (map (juxt :key :label)) metrics/metrics))

(defn- label
  [metric]
  (labels metric (name metric)))

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

(defn- tile
  [class n text]
  [:div {:class (str "tile " class)}
   [:div {:class "n"} n]
   [:div {:class "l"} text]])

(defn- table
  "A scrolling table. Headers are strings or [attrs string]; rows are
  sequences of cells, each content or [:td ...]."
  [headers rows]
  (let [th #(if (vector? %) [:th (first %) (second %)] [:th %])
        td #(if (and (vector? %) (= :td (first %))) % [:td %])]
    [:div {:class "scroll"}
     [:table
      [:thead [:tr (map th headers)]]
      [:tbody (for [row rows] [:tr (map td row)])]]]))

(defn- brick-cell
  [brick]
  (list (:name brick) " " [:span {:class "type"} (name (:type brick))]))

(def ^:private status-order
  {:new 0 nil 0 :indirect 1 :existing 2})

(defn- violations-table
  [violations comparison]
  (if (empty? violations)
    [:p {:class "none"} "No thresholds exceeded."]
    (table (cond-> ["Level" "Brick" "Metric" "Detail" "Location"]
             comparison (conj "Status"))
      (for [{:keys [brick metric level message status] :as v}
            (sort-by (juxt (comp status-order :status)
                       #(if (= :error (:level %)) 0 1)
                       (comp :name :brick))
              violations)]
        (cond-> [(badge (name level) (name level))
                 (brick-cell brick)
                 (label metric)
                 message
                 (location (:location v))]
          comparison (conj (badge (str "status " (name status))
                             (name status))))))))

(defn- resolved-table
  [resolved]
  (table ["Brick" "Metric" "Detail"]
    (for [{:keys [brick metric message]} resolved]
      [(brick-cell brick) (label metric) message])))

(defn- delta-cell
  [base head]
  (cond
    (nil? head) [:td {:class "num"}]
    (nil? base) [:td {:class "num"} head " " [:span {:class "type"} "new"]]
    (= base head) [:td {:class "num"} head]
    :else [:td {:class "num"}
           base " → " head " "
           [:span {:class (if (> head base) "up" "down")}
            "(" (if (> head base) "+" "") (- head base) ")"]]))

(defn- changes-table
  [bricks {:keys [changed-bricks base-metrics]}]
  (let [changed (filter #(changed-bricks (:name (:brick %))) bricks)]
    (if (empty? changed)
      [:p {:class "none"} "No bricks changed."]
      (table (into ["Brick"]
               (map (fn [{:keys [label description]}]
                      [{:class "num" :title description} label]))
               metrics/metrics)
        (for [{:keys [brick] :as m} changed
              :let [base (base-metrics (:name brick))]]
          (into [(brick-cell brick)]
            (for [{k :key} metrics/metrics]
              (delta-cell (get base k) (get-in m [:metrics k])))))))))

(defn- flagged-cells
  [violations]
  (reduce
    (fn [acc {:keys [brick metric level message]}]
      (-> acc
        (update-in [[(:name brick) metric] :level] worse level)
        (update-in [[(:name brick) metric] :messages] (fnil conj []) message)))
    {}
    (filter introduced? violations)))

(defn- metric-cell
  [{:keys [level messages]} value]
  [:td {:class (str "num" (when level (str " " (name level))))
        :title (when messages (str/join "\n" messages))}
   value])

(defn- metrics-table
  [bricks violations]
  (let [flagged (flagged-cells violations)]
    (table (into ["Brick"]
             (map (fn [{:keys [label description]}]
                    [{:class "num" :title description} label]))
             metrics/metrics)
      (for [{:keys [brick] :as m} bricks]
        (into [(brick-cell brick)]
          (for [{k :key} metrics/metrics]
            (metric-cell (flagged [(:name brick) k])
              (get-in m [:metrics k]))))))))

(defn- functions-table
  [bricks n]
  (let [functions (->> bricks
                    (mapcat (fn [{:keys [brick functions]}]
                              (map #(assoc % :brick brick) functions)))
                    (sort-by (juxt (comp - :complexity) :file :line))
                    (take n))]
    (if (empty? functions)
      [:p {:class "none"} "No functions found."]
      (table [[{:class "num"} "Complexity"] "Function" "Brick" "Location"]
        (for [{:keys [complexity name brick] :as f} functions]
          [[:td {:class "num"} complexity]
           [:code name]
           (:name brick)
           (location (dissoc f :name))])))))

(defn- rule-text
  [{:keys [rule value]}]
  (case rule
    :max (str "at most " value)
    :min (str "at least " value)
    :std-devs (str "at most " value " standard deviations above other bricks")
    :max-increase (str "increase of at most " value)
    :max-increase-percent (str "increase of at most " value "%")
    (pr-str rule)))

(defn- rules-table
  [thresholds]
  (table ["Metric" "Rule" "Level"]
    (for [[metric rules] (sort-by key thresholds)
          {:keys [level] :or {level :error} :as rule} rules]
      [(label metric) (rule-text rule) (badge (name level) (name level))])))

(defn- summary-tiles
  [{:keys [bricks violations comparison]}]
  (let [counts (frequencies (map :level (filter introduced? violations)))
        qualifier (if comparison "new " "")]
    [:div {:class "tiles"}
     (tile "" (count bricks) "bricks")
     (when comparison
       (tile "" (count (:changed-bricks comparison)) "changed bricks"))
     (tile (if (pos? (counts :error 0)) "error" "ok")
       (counts :error 0) (str qualifier "errors"))
     (tile (if (pos? (counts :warning 0)) "warning" "ok")
       (counts :warning 0) (str qualifier "warnings"))
     (when comparison
       (tile "ok" (count (:resolved comparison)) "resolved"))]))

(defn- meta-line
  [{:keys [generated-at comparison]}]
  [:p {:class "meta"}
   "Generated " generated-at
   (when-let [{:keys [base-ref base-rev]} comparison]
     (list " · compared with " [:code base-ref] " (merge-base "
       [:code (subs base-rev 0 (min 12 (count base-rev)))] ")"))])

(defn- sections
  [{:keys [bricks violations thresholds comparison] :as report}]
  (list
    (summary-tiles report)
    [:h2 "Violations"]
    (violations-table violations comparison)
    (when comparison
      (list
        (when (seq (:resolved comparison))
          (list [:h2 "Resolved"] (resolved-table (:resolved comparison))))
        [:h2 "Changed bricks"]
        (changes-table bricks comparison)))
    [:h2 "Metrics"]
    (metrics-table bricks violations)
    [:h2 "Most complex functions"]
    (functions-table bricks 15)
    [:h2 "Thresholds"]
    (rules-table thresholds)
    (when (seq (:changes report))
      (list [:h2 "Change thresholds"] (rules-table (:changes report))))))

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
