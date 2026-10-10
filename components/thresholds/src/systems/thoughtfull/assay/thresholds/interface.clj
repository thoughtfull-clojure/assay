(ns systems.thoughtfull.assay.thresholds.interface
  "Check brick measurements and findings against thresholds.

  The config maps each report section (:dependencies, :complexity,
  :modularity, :io, :errors, :tests) to its metrics, and each metric to
  its settings: a :warning and an :error threshold, and any options the
  metric has, such as :allow for :library-spread. A threshold is a
  number, or for a metric of a brick's own value, {:std-devs k}, which
  flags a brick more than k sample standard deviations past the mean of
  the other bricks the metric checks (with at least :min-peers of them,
  default 3).

  What a threshold means comes from the metric (see metrics/metrics): its
  :direction says whether values above (:max) or below (:min) the limit
  are flagged, its :checks which brick types are checked, and its :kind
  what is compared. A value past both thresholds is an error. A nil
  threshold turns that level off, and a nil metric turns the metric off."
  (:require
   [systems.thoughtfull.assay.thresholds.core :as core]))

(def default-config
  "The settings used when no configuration overrides them, shaped like the
  config: section to metric to settings."
  core/default-config)

(defn merge-config
  "Merge config over the defaults, metric by metric and setting by
  setting, so {:complexity {:function-depth {:error 12}}} keeps the
  default warning. Throws, with :type
  :systems.thoughtfull.assay.thresholds.core/invalid-config in its data,
  for an unknown section, metric, or setting, a metric under the wrong
  section, a malformed threshold, or keys of the old config shape."
  [config]
  (core/merge-config config))

(defn settings
  "The settings of metric k in merged config, or nil when it has none or
  is off."
  [config k]
  (core/settings config k))

(defn check
  "Check measurements (as returned by the dependencies component) and
  findings, a map of metric key to findings (each a map of :brick, and
  optionally :value, :subject, :location, :message, and :historical?),
  against merged config. Returns a map of :bricks, the measurements with
  the values of :function, :finding, and :count metrics added to
  :metrics, and :violations, each a map of :metric, :section, :kind,
  :direction, :brick, :value, :limit, :level, :message, and when known
  :subject, :location, :stats, and :historical?; a function's violation
  also has :function-line, the line it is defined on, where :location is
  at the metric's line (such as the deepest form, for depth)."
  [config measurements findings]
  (core/check config measurements findings))

(defn worse-level
  "The more severe of two levels: :error, :warning, or nil for none."
  [a b]
  (core/worse-level a b))

(defn threshold-text
  "Threshold t of metric (a registry entry) in short notation, the values
  it flags, such as \"> 10\", \"< 0.5\", or \"> mean + 2σ\"; nil for nil."
  [metric t]
  (core/threshold-text metric t))

(defn describe
  "Metric k's thresholds in merged config, such as \"warning > 8, error >
  10\", or nil when it has none."
  [config k]
  (core/describe config k))

(defn rows
  "Violations grouped into the rows reports show, one per finding: a
  function's violations across its metrics, a library's spread across its
  bricks, and a duplicate's copies are one row each. Each row is a map of
  :group (the metric key, or :function-rows for a function's row), :section,
  :metric (its first violation's), :subject, :value, :level (the worst),
  :status (new if any violation is), :bricks, :locations, and
  :violations. Rows are most severe first: errors, then by how far past
  the limit."
  [violations]
  (core/rows violations))

(defn by-severity
  "Violations sorted most severe first: errors before warnings, then by how
  far each value is past its limit, relative to the limit."
  [violations]
  (core/by-severity violations))
