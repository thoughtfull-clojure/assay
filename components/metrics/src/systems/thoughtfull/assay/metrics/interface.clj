(ns systems.thoughtfull.assay.metrics.interface
  "Measure Clojure source files and Polylith bricks."
  (:require
   [systems.thoughtfull.assay.metrics.core :as core]))

(def function-metrics
  "Ordered function metrics, each a map of :key, :label, :description (a
  short tooltip), and :explanation (a longer legend entry)."
  core/function-metrics)

(defn measure-source
  "Measure a string of Clojure source from file (a path used for locations).
  Returns a map of :file, :ns and :requires (from its ns form, if any),
  :forms, :top-level-forms, :max-nesting-depth, :max-nesting-location,
  :functions, and :definitions. Each definition (def, defn, defmulti, and
  so on) is a map of :name (a symbol), :line, and :references, the set of
  symbols in its body. Each function is a map of :name, :file, :line, :complexity,
  :depth (with :depth-line, the line of its deepest form), :forms, and
  :params."
  [file source]
  (core/measure-source file source))

(defn measure-brick
  "Measure every source file of brick (as returned by the workspace
  component), reading files relative to root. Returns a map of :brick,
  :metrics (metric key to number, or nil when undefined), :locations
  (metric key to the {:file :line :name} responsible for a max-* metric),
  :functions, and :sources (each source file's :file, :ns, :requires,
  :forms, and :definitions)."
  [root brick]
  (core/measure-brick root brick))

(def columns
  "Ordered display columns for brick metrics, each a map of :label,
  :description (a short tooltip), :explanation (a longer legend entry), and
  :keys, the metrics it shows. Mean and max function
  complexity share one column."
  core/columns)

(defn format-value
  "Brick metric k's value v for display: decimals to their precision, nil
  as a dash."
  [k v]
  (core/format-value k v))

(defn column-text
  "A column's values from a map of metrics, joined with \" / \"."
  [column metrics]
  (core/column-text column metrics))

(defn totals
  "Brick metrics totalled across measurements: sums of counts, maxima of
  maxima, means of ratios, and the mean complexity of all functions.
  Metrics with no meaningful total (afferent, efferent) are omitted."
  [measurements]
  (core/totals measurements))

(defn label
  "The display label of a violation, or of any map of :metric and optionally
  :scope (:brick, the default, or :function). A :label in the map wins,
  then the registry's label, then the metric's name."
  [m]
  (core/label m))
