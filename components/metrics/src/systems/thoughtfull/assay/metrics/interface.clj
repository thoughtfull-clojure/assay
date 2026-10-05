(ns systems.thoughtfull.assay.metrics.interface
  "Measure Clojure source files and Polylith bricks."
  (:require
   [systems.thoughtfull.assay.metrics.core :as core]))

(defn measure-source
  "Measure a string of Clojure source from file (a path used for locations).
  Returns a map of :file, :ns and :requires (from its ns form, if any),
  :forms, :top-level-forms,
  :functions (each also has :ns), and :definitions. Each definition (def, defn, defmulti, and
  so on) is a map of :name (a symbol), :line, and :references, the set of
  symbols in its body. Each function is a map of :name, :file, :line, :complexity,
  :depth (with :depth-line, the line of its deepest form), :forms, and
  :params."
  [file source]
  (core/measure-source file source))

(defn measure-brick
  "Measure every source file of brick (as returned by the workspace
  component), reading files relative to root. Returns a map of :brick,
  :metrics (metric key to number, or nil when undefined), :functions, and :sources (each source file's :file, :ns, :requires,
  :forms, and :definitions)."
  [root brick]
  (core/measure-brick root brick))

(def columns
  "Ordered display columns for brick metrics, each a map of :label,
  :description (a short tooltip), :explanation (a longer legend entry), and
  :keys, the metrics it shows."
  core/columns)

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
  nil when none do. Metrics that only describe components, such as
  abstractness and afferent coupling, average components only."
  [measurements]
  (core/averages measurements))

(def outlier-std-devs
  "How many standard deviations from the mean reports treat as an outlier."
  core/outlier-std-devs)

(defn outliers
  "Brick metrics at least k standard deviations from the mean of every
  brick that has a value, as a map of [brick-name metric-key] to :z (signed
  standard deviations from the mean), :mean, :std-dev (of the population),
  and :peers, :bricks or :components (for metrics that only describe
  components, which leave bases out). A metric needs at least 3 values and
  some variation."
  [measurements k]
  (core/outliers measurements k))

(defn label
  "The display label of a violation, or of any map of :metric and optionally
  :scope (:brick, the default, or :function). A :label in the map wins,
  then the registry's label, then the metric's name."
  [m]
  (core/label m))

(defn function-id
  "A function's identity across a workspace: its name qualified by its
  namespace, since bricks often have same-named functions in different
  namespaces."
  [function]
  (core/function-id function))
