(ns systems.thoughtfull.assay.text-report.interface
  "Render a report as plain text for a terminal, such as a Git hook.

  A report is a map of :bricks (measurements from the metrics component),
  :violations (from the thresholds component), and, when compared with a
  base, :comparison (from the baseline component)."
  (:require
   [systems.thoughtfull.assay.text-report.core :as core]))

(defn render
  "One line per violation, then a summary line. When the report has a
  comparison, only violations that a change introduced are listed."
  [report]
  (core/render report))
