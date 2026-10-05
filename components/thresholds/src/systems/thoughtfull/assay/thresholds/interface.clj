(ns systems.thoughtfull.assay.thresholds.interface
  "Compare brick and function measurements to threshold rules.

  Configuration has two maps from metric key to a vector of rules:
  :brick-thresholds for brick metrics and :function-thresholds for each
  function's metrics. Each rule is a map with a :rule type, a :value, and an
  optional :level (:error, the default, or :warning):

  - {:rule :max :value n} flags a value above n.
  - {:rule :min :value n} flags a value below n.
  - {:rule :std-devs :value k} flags a brick whose metric is more than k
    standard deviations above the mean of the other bricks of the same type
    (components are compared with components, bases with bases). It needs
    at least :min-peers other bricks (default 3) and is skipped otherwise.
    Brick thresholds only.

  Brick rules take an optional :types, a set of brick types (:component,
  :base) the rule applies to. Rules skip metrics with no value."
  (:require
   [systems.thoughtfull.assay.thresholds.core :as core]))

(def default-config
  "Thresholds used when no configuration overrides them, as a map of
  :brick-thresholds and :function-thresholds."
  core/default-config)

(defn merge-config
  "Merge configured :brick-thresholds and :function-thresholds over the
  defaults. A configured metric replaces all default rules for that metric;
  an empty vector disables it."
  [config]
  (core/merge-config config))

(defn check
  "Check measurements (as returned by the metrics component) against merged
  config. Returns a vector of violations, each a map of :scope (:brick or
  :function), :brick, :metric, :value, :rule, :level, :limit, :message, and,
  when known, :location, :stats, and :subject (the function name)."
  [config measurements]
  (core/check config measurements))
