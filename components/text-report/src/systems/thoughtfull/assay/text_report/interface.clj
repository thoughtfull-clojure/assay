(ns systems.thoughtfull.assay.text-report.interface
  "Render a report as plain text for a terminal, such as a Git hook.

  A report is a map of :bricks (measurements from the metrics component),
  :violations (from the thresholds component), and, when compared with a
  base, :comparison (from the baseline component)."
  (:require
   [systems.thoughtfull.assay.text-report.core :as core]))

(defn render
  "A heading for each report section with violations, then a line for
  each finding (see thresholds/rows), most severe first: a function's
  violations, across its metrics, are one line. Then a summary line,
  counting findings. When the report has a comparison, only findings
  that a change introduced are listed."
  [report]
  (core/render report))
