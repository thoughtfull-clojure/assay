(ns systems.thoughtfull.assay.thresholds.interface
  "Compare brick measurements to threshold rules.

  Thresholds map a metric key to a vector of rules. Each rule is a map with
  a :rule type, a :value, and an optional :level (:error, the default, or
  :warning):

  - {:rule :max :value n} flags a brick whose metric is above n.
  - {:rule :min :value n} flags a brick whose metric is below n.
  - {:rule :std-devs :value k} flags a brick whose metric is more than k
    standard deviations above the mean of the other bricks of the same type
    (components are compared with components, bases with bases). It needs
    at least :min-peers other bricks (default 3) and is skipped otherwise."
  (:require
   [systems.thoughtfull.assay.thresholds.core :as core]))

(def default-thresholds
  "Thresholds used when no configuration overrides them."
  core/default-thresholds)

(defn merge-thresholds
  "Merge configured thresholds over the defaults. A configured metric
  replaces all default rules for that metric; an empty vector disables it."
  [thresholds]
  (merge default-thresholds thresholds))

(defn check
  "Check measurements (as returned by the metrics component) against
  thresholds. Returns a vector of violations, each a map of :brick, :metric,
  :value, :rule, :level, :limit, :message, and, when known, :location and
  :stats."
  [thresholds measurements]
  (core/check thresholds measurements))
