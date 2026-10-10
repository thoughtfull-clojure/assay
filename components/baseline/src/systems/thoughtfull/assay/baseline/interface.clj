(ns systems.thoughtfull.assay.baseline.interface
  "Compare a workspace with a baseline, such as the branch a pull request
  will merge into, so that CI can fail on what a change introduces rather
  than on problems that were already there.

  A function violation that was in base is still new when the change made
  it worse, so a change can't make an already complex function more
  complex unnoticed."
  (:require
   [systems.thoughtfull.assay.baseline.core :as core]))

(defn changed-bricks
  "Names of the bricks in measurements whose source files are among
  changed-files (paths relative to the workspace root)."
  [measurements changed-files]
  (core/changed-bricks measurements changed-files))

(defn compare-reports
  "Compare head with base, two reports of :bricks, :violations, and :edges.
  Returns head with:

  - each violation's :status set to :new (in a changed brick and not in
    base, or a function violation whose value got worse since base, its
    message ending with what the value was), :existing (also in base), or
    :indirect (not in base, but in a brick that did not change, as when a
    statistical threshold moves); a :historical? violation, from history
    rather than code, is :existing; and its :base-value set to the
    metric's value in base;
  - each of its :edges that base doesn't have marked :new? true;
  - :comparison, a map of :changed-bricks (a set of names), :base-metrics
    (brick name to metrics), and :resolved (violations in base but not in
    head).

  Violations are matched by brick, metric, and :subject (a function name
  or finding), so a function that moves is still the same."
  [base head changed-files]
  (core/compare-reports base head changed-files))
