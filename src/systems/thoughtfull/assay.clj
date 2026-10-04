(ns systems.thoughtfull.assay
  "Gather code metrics about Clojure source."
  (:require
   [systems.thoughtfull.assay.metrics :as metrics]
   [systems.thoughtfull.assay.parse :as parse]))

(defn measure
  "Apply every registered metric to a parsed :forms node, returning a map
  of metric name to value."
  [forms]
  (update-vals metrics/metrics #(% forms)))

(defn analyze-string
  "Metrics for a string of Clojure source."
  [s]
  (measure (parse/parse-string s)))

(defn analyze-file
  "Metrics for a Clojure source file."
  [f]
  (measure (parse/parse-file f)))
