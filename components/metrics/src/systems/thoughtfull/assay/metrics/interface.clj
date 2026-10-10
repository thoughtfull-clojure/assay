(ns systems.thoughtfull.assay.metrics.interface
  "Measure Clojure source files and Polylith bricks."
  (:require
   [systems.thoughtfull.assay.metrics.core :as core]))

(defn measure-source
  "Measure a string of Clojure source from file (a path used for locations).
  Returns a map of :file, :ns and :requires (from its ns form, if any),
  :forms, :top-level-forms, :functions (each also has :ns), :definitions,
  :keywords, :fragments, :mutable-state, :throws, :catches, and :interop
  (a count of Java interop forms).

  Each definition (def, defn, defmulti, and so on) is a map of :name (a
  symbol), :line, :references, the set of symbols in its body, and
  :throws?, true if its body throws. Each
  function is a map of :name, :file, :line, :complexity, :depth (with
  :depth-line, the line of its deepest form), :forms, and :params. Each
  :mutable-state entry is a top-level atom, ref, agent, volatile, or
  dynamic var, or an alter-var-root call, as a map of :name, :line, and
  :kind. Each throw is a map of :line and :kind (:typed, :untyped, or
  :unknown ex-info, a :java exception, or a :rethrow), and each catch
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
  function's value and line), and :format and :precision. See the
  comment on the registry in core for what each kind means."
  core/metrics)

(defn metric
  "The registry entry for metric key k, with its defaults filled in, or
  nil."
  [k]
  (core/metric k))

(def columns
  "Ordered display columns for brick metrics, each a map of :label,
  :description (a short tooltip), :explanation (a longer legend entry), and
  :keys, the metrics it shows."
  core/columns)

(def sections
  "Report sections in order, each a map of :key, :label, and :columns, the
  columns (as in columns) it shows: dependencies, complexity, modularity,
  I/O and mutability, error handling, and tests. :components-only is true
  for a section whose table leaves bases out."
  core/sections)

(defn violation-section
  "The key of the section (as in sections) that a violation, or any map
  of :metric, belongs to: its metric's."
  [violation]
  (core/violation-section violation))

(defn section-measurements
  "The measurements a section's table shows: components only, for a
  section marked :components-only, otherwise all of them."
  [section measurements]
  (core/section-measurements section measurements))

(defn format-value
  "Brick metric k's value v for display: decimals to their precision,
  other whole numbers as they are and fractions (such as averages) to one
  place, and nil as a dash."
  [k v]
  (core/format-value k v))

(defn column-text
  "A column's values from a map of metrics, joined with \" / \"."
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
  "Brick metrics at least k standard deviations from the mean of every
  brick that has a value, as a map of [brick-name metric-key] to :z (signed
  standard deviations from the mean), :mean, :std-dev (of the population),
  and :peers, :bricks or :components (for metrics that check only
  components, which leave bases out). A metric needs at least 3 values and
  some variation."
  [measurements k]
  (core/outliers measurements k))

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
