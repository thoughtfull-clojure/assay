(ns systems.thoughtfull.assay.github-report.core
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.metrics.interface :as metrics]))

(def ^:private labels
  (into {} (map (juxt :key :label)) metrics/metrics))

(defn- label
  [metric]
  (labels metric (name metric)))

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

(defn- annotation
  [{:keys [brick metric message location status] :as violation}]
  (let [props (cond-> []
                (:file location) (conj (str "file=" (escape-property
                                                      (:file location))))
                (:line location) (conj (str "line=" (:line location)))
                :always (conj (str "title=" (escape-property
                                              (str (brick-label brick) ": "
                                                (label metric))))))
        text (str (label metric) " " message
               (when (:name location) (str " (" (:name location) ")"))
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

(defn- worse
  [a b]
  (if (some #{:error} [a b]) :error (or a b)))

(defn- flagged-cells
  "Map of [brick name, metric] to the worst level of violations that a
  change introduced or, without a comparison, of all violations."
  [violations]
  (reduce
    (fn [acc {:keys [brick metric level]}]
      (update acc [(:name brick) metric] worse level))
    {}
    (remove (comp #{:existing :indirect} :status) violations)))

(defn- headline
  [{:keys [bricks violations comparison]}]
  (let [counted (remove (comp #{:existing :indirect} :status) violations)
        counts (frequencies (map :level counted))]
    (str (count bricks) " bricks, "
      (counts :error 0) " " (if comparison "new " "") "errors, "
      (counts :warning 0) " " (if comparison "new " "") "warnings."
      (when comparison
        (str " Compared with `" (:base-ref comparison) "` (merge-base `"
          (subs (:base-rev comparison) 0 (min 12 (count (:base-rev comparison))))
          "`), " (count (:changed-bricks comparison)) " bricks changed.")))))

(def ^:private status-order
  {:new 0 nil 0 :indirect 1 :existing 2})

(defn- violations-section
  [{:keys [violations comparison]}]
  (when (seq violations)
    (str "### Violations\n\n"
      (table (cond-> ["Level" "Brick" "Metric" "Detail"]
               comparison (conj "Status"))
        (for [{:keys [brick metric level message location status]}
              (sort-by (comp status-order :status) violations)]
          (cond-> [(level-mark level)
                   (brick-label brick)
                   (label metric)
                   (str message (location-text location))]
            comparison (conj (name status))))))))

(defn- resolved-section
  [{:keys [comparison]}]
  (when-let [resolved (seq (:resolved comparison))]
    (str "### Resolved\n\n"
      (table ["Brick" "Metric" "Detail"]
        (for [{:keys [brick metric message]} resolved]
          [(brick-label brick) (label metric) message])))))

(defn- delta-text
  [base head]
  (cond
    (nil? head) ""
    (nil? base) (str head " (new)")
    (= base head) (str head)
    :else (str base " → " head " (" (if (> head base) "+" "") (- head base) ")")))

(defn- changes-section
  [{:keys [bricks comparison]}]
  (when-let [changed (seq (filter #((:changed-bricks comparison)
                                    (:name (:brick %)))
                            bricks))]
    (str "### Changed bricks\n\n"
      (table (into ["Brick"] (map :label metrics/metrics))
        (for [{:keys [brick] :as m} changed
              :let [base (get-in comparison [:base-metrics (:name brick)])]]
          (into [(brick-label brick)]
            (for [{k :key} metrics/metrics]
              (delta-text (get base k) (get-in m [:metrics k])))))))))

(defn- metric-cell
  [level value]
  (case level
    :error (str "**" value "** ❌")
    :warning (str "**" value "** ⚠️")
    value))

(defn- metrics-section
  [{:keys [bricks violations]}]
  (let [flagged (flagged-cells violations)]
    (str "### Metrics\n\n"
      (table (into ["Brick"] (map :label metrics/metrics))
        (for [{:keys [brick] :as m} bricks]
          (into [(brick-label brick)]
            (for [{k :key} metrics/metrics]
              (metric-cell (flagged [(:name brick) k])
                (get-in m [:metrics k])))))))))

(defn summary
  [report]
  (str (str/join "\n\n"
         (remove nil?
           [(str "## Assay: " (:workspace report))
            (headline report)
            (violations-section report)
            (resolved-section report)
            (when (:comparison report) (changes-section report))
            (metrics-section report)]))
    "\n"))
