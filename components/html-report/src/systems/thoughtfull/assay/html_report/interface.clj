(ns systems.thoughtfull.assay.html-report.interface
  "Render a report as a standalone HTML page.

  A report is a map of :workspace (a name), :generated-at (a string),
  :bricks (measurements from the metrics component), :violations (from the
  thresholds component), and :thresholds (the rules that were applied)."
  (:require
   [systems.thoughtfull.assay.html-report.core :as core]))

(defn render
  "The report as a string of HTML with no external resources."
  [report]
  (core/render report))
