(ns systems.thoughtfull.assay.metrics.interface
  "Measure Clojure source files and Polylith bricks."
  (:require
   [systems.thoughtfull.assay.metrics.core :as core]))

(def metrics
  "Ordered brick metrics, each a map of :key, :label, and :description."
  core/metrics)

(defn measure-source
  "Measure a string of Clojure source from file (a path used for locations).
  Returns a map of :lines, :forms, :top-level-forms, :max-nesting-depth,
  :max-nesting-location, and :functions. Each function is a map of :name,
  :file, :line, and :complexity."
  [file source]
  (core/measure-source file source))

(defn measure-brick
  "Measure every source file of brick (as returned by the workspace
  component), reading files relative to root. Returns a map of :brick,
  :metrics (metric key to number), :locations (metric key to the
  {:file :line :name} responsible for a max-* metric), and :functions."
  [root brick]
  (core/measure-brick root brick))
