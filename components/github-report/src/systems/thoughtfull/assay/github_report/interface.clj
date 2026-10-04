(ns systems.thoughtfull.assay.github-report.interface
  "Render a report for GitHub Actions: workflow command annotations and a
  Markdown job summary.

  A report is a map of :workspace (a name), :bricks (measurements from the
  metrics component), and :violations (from the thresholds component)."
  (:require
   [systems.thoughtfull.assay.github-report.core :as core]))

(defn annotations
  "Workflow commands (::error and ::warning lines) for each violation. Print
  them to stdout in a workflow step."
  [report]
  (core/annotations report))

(defn summary
  "Markdown for the job summary. Append it to the file named by the
  GITHUB_STEP_SUMMARY environment variable."
  [report]
  (core/summary report))
