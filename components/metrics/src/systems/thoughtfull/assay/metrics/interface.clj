(ns systems.thoughtfull.assay.metrics.interface
  "Measure Clojure source files and Polylith bricks."
  (:require
   [systems.thoughtfull.assay.metrics.core :as core]))

(defn measure-source
  "Measure a string of Clojure source from file (a path used for locations).
  Returns a map of :file, :ns and :requires (from its ns form, if any),
  :forms, :top-level-forms, :functions (each also has :ns), :definitions,
  :keywords, :fragments, :mutable-state, :throws, :catches, and :interop
  (a count of host interop forms).

  Each definition (def, defn, defmulti, and so on) is a map of :name (a
  symbol), :line, :references, the set of symbols in its body, and
  :throws?, true if its body throws. Each
  function is a map of :name, :file, :line, :complexity, :depth (with
  :depth-line, the line of its deepest form), :forms, and :params. Each
  :mutable-state entry is a top-level atom, ref, agent, volatile, or
  dynamic var, or an alter-var-root call, as a map of :name, :line, and
  :kind. Each throw is a map of :line and :kind (:typed, :untyped, or
  :unknown ex-info, a :host exception, or a :rethrow), and each catch
  clause a map of :line, :class, and :broad?."
  [file source]
  (core/measure-source file source))

(defn measure-test-source
  "Measure a string of Clojure test source from file. Returns a map of
  :file, :ns and :requires (from its ns form), :forms, :tests (each
  deftest as {:name :line :forms :assertions}, counting is and are),
  :hazards (with-redefs, Thread/sleep, alter-var-root, and top-level
  mutable state, each {:line :kind}), and :references, every symbol in
  the file."
  [file source]
  (core/measure-test-source file source))

(defn measure-brick
  "Measure every source file of brick (as returned by the workspace
  component), reading files relative to root. Returns a map of :brick,
  :metrics (metric key to number, or nil when undefined), :functions, and
  :sources (each source file's :file, :ns, :requires, :forms,
  :definitions, :keywords, :fragments, :mutable-state, :throws, and
  :catches), and :tests, each test file as from measure-test-source."
  [root brick]
  (core/measure-brick root brick))

(def metrics
  "The metric registry, in display order. Each metric is a map of :key,
  :section (the report section, which is also its category in the
  config), :label, :description, and how its thresholds apply: :kind
  (:brick, :function, :finding, or :count), :direction (:max or :min),
  :types (the brick types it describes), :checks (the brick types its
  thresholds check), and optionally :default (its default thresholds, a
  map of :warning and :error), :options (the defaults of its other
  settings), :function-key and :line-key (for a :function metric, the
  function's value and line), :gate (other metrics' least values for it to
  be checked), :outline? (true for a ratio, density, or mean that reports
  outline), :aggregate (for a :finding metric, :count, :sum, or :value),
  and :format (:decimal or :percent) and :precision. See the comment on
  the registry in core for more."
  core/metrics)

(defn metric
  "The registry entry for metric key k, with its defaults filled in, or
  nil."
  [k]
  (core/metric k))

(defn applies?
  "True if metric (a registry entry) checks measurement: the brick is of a
  type it checks, and its metrics pass the metric's :gate."
  [metric measurement]
  (core/applies? metric measurement))

(defn subject-key
  "The key, in a brick's metrics, of the subject of :aggregate :value
  metric k, which reports show with its value."
  [k]
  (core/subject-key k))

(def columns
  "Ordered display columns for brick metrics, each a map of :label,
  :description (a short tooltip), :explanation (a longer legend entry), and
  :keys, the metrics it shows."
  core/columns)

(def sections
  "Report sections in order, each a map of :key, :label, and :columns, the
  columns (as in columns) it shows: dependencies, complexity, modularity,
  I/O and mutability, error handling, and tests."
  core/sections)

(defn violation-section
  "The key of the section (as in sections) that a violation, or any map
  of :metric, belongs to: its metric's."
  [violation]
  (core/violation-section violation))

(defn section-measurements
  "The measurements a section's table shows: components only, for a
  section marked :components-only, otherwise all of them. No section is
  marked today: every table shows bases too."
  [section measurements]
  (core/section-measurements section measurements))

(defn format-value
  "Brick metric k's value v for display: percentages as such, decimals to
  their precision,
  other whole numbers as they are and fractions (such as averages) to one
  place, and nil as a dash."
  [k v]
  (core/format-value k v))

(defn column-text
  "A column's values from a map of metrics, joined with \" / \", each after
  its subject when it has one, as in \"patient (1%)\"."
  [column metrics]
  (core/column-text column metrics))

(defn averages
  "Each brick metric's mean across the measurements that have a value, or
  nil when none do, of the brick types the metric checks: components
  only for metrics such as afferent coupling and cohesion."
  [measurements]
  (core/averages measurements))

(def outlier-std-devs
  "How many standard deviations from the mean reports treat as an outlier."
  core/outlier-std-devs)

(defn outliers
  "Values of outlined metrics (see :outline? in metrics) at least k sample
  standard deviations past the mean of the other bricks the metric
  applies to, in the metric's :direction, as the :std-devs threshold
  compares. A map of [brick-name metric-key] to :z (signed standard
  deviations from the others' mean), :mean, :std-dev, and :peers,
  :bricks or :components (for metrics that check only components). A
  metric needs at least 4 values."
  [measurements k]
  (core/outliers measurements k))

(defn detail
  "What explains metric k's value in a brick's metrics, for a tooltip: the
  counts behind a ratio, such as \"16 of 41 public interface definitions
  can throw\", which side of the main sequence a component is on, or the
  kinds of isolation hazards. nil when there is nothing to add."
  [k metrics]
  (core/detail k metrics))

(defn label
  "The display label of a violation, or of any map of :metric. A :label in
  the map wins, then the registry's label, then the metric's name."
  [m]
  (core/label m))

(defn function-id
  "A function's identity across a workspace: its name qualified by its
  namespace, since bricks often have same-named functions in different
  namespaces."
  [function]
  (core/function-id function))
