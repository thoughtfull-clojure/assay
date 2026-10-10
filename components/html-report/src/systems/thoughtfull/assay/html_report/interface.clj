(ns systems.thoughtfull.assay.html-report.interface
  "Render a report as a standalone HTML page.

  A report is a map of :workspace (a name), :generated-at (a string),
  :bricks (measurements from the metrics component), :violations (from the
  thresholds component), :thresholds (the merged config), :configured
  (the config as given, to mark what it sets), and optionally
  :graph-violations, the violations the dependency graph draws when
  :violations leaves some out. Violations are shown as findings (see
  thresholds/rows): a summary grid, then each section's under its table."
  (:require
   [systems.thoughtfull.assay.html-report.core :as core]))

(defn render
  "The report as a string of HTML with no external resources."
  [report]
  (core/render report))
