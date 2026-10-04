(ns systems.thoughtfull.assay.baseline.interface
  "Compare a workspace with a baseline, such as the branch a pull request
  will merge into, so that CI can fail on what a change introduces rather
  than on problems that were already there.

  Change thresholds map a metric key to a vector of rules applied to the
  change in a brick's metric since the baseline:

  - {:rule :max-increase :value n} flags an increase of more than n. A brick
    that is new since the baseline counts its whole value as the increase.
  - {:rule :max-increase-percent :value p} flags an increase of more than p
    percent. It is skipped for bricks that are new or were zero.

  Rules take an optional :level, :error (the default) or :warning."
  (:require
   [systems.thoughtfull.assay.baseline.core :as core]))

(defn changed-bricks
  "Names of the bricks in measurements whose source files are among
  changed-files (paths relative to the workspace root)."
  [measurements changed-files]
  (core/changed-bricks measurements changed-files))

(defn compare-reports
  "Compare head with base, two reports of :bricks and :violations. Returns
  head with:

  - each violation's :status set to :new (in a changed brick and not in
    base), :existing (also in base), or :indirect (not in base, but in a
    brick that did not change, as when a statistical threshold moves), and
    its :base-value set to the metric's value in base;
  - a :new violation added for each change threshold exceeded by a changed
    brick;
  - :comparison, a map of :changed-bricks (a set of names), :base-metrics
    (brick name to metrics), and :resolved (violations in base but not in
    head)."
  [base head changed-files change-thresholds]
  (core/compare-reports base head changed-files change-thresholds))
