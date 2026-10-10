(ns systems.thoughtfull.assay.github-report.interface
  "Render a report for GitHub Actions: workflow command annotations and a
  Markdown job summary.

  A report is a map of :workspace (a name), :bricks (measurements from the
  metrics component), :violations (from the thresholds component),
  :thresholds (the merged config), and optionally :graph-violations, the
  violations the dependency graph draws when :violations leaves some out.
  Violations are shown as findings (see thresholds/rows)."
  (:require
   [systems.thoughtfull.assay.github-report.core :as core]))

(defn annotations
  "Workflow commands (::error, ::warning, and for findings a change didn't
  introduce, ::notice lines) for each finding: one at a function's
  definition for all its metrics, and one at each location of a finding
  with several, such as each brick's require of a spread library. Print
  them to stdout in a workflow step."
  [report]
  (core/annotations report))

(defn summary
  "Markdown for the job summary: a grid counting each metric's violations
  (and with a comparison, the new ones listed), then each section's
  table, legend with thresholds, and violations by metric. Append it to
  the file named by the GITHUB_STEP_SUMMARY environment variable."
  [report]
  (core/summary report))
