(ns systems.thoughtfull.assay.dependencies.interface
  "Brick dependency metrics from namespace requires, after Robert Martin's
  package metrics, adapted to Polylith: a brick depends on an interface, and
  so on every component that implements it.

  - Afferent (Ca): bricks that depend on this brick's interface.
  - Efferent (Ce): interfaces this brick depends on.
  - Instability: Ce / (Ca + Ce), undefined for a brick with neither.
  - Abstractness: 1 - interface forms / all forms. A small interface over
    a large implementation is abstract; bases are 0.

  Dependency rules map a check to a level (:error or :warning), or to nil to
  turn it off:

  - :stable-dependencies flags a dependency on a less stable brick (the
    Stable Dependencies Principle).
  - :cycles flags bricks that depend on each other, directly or not.
  - :new-dependencies flags a dependency that is not in the base, when
    comparing with one (applied by the baseline component)."
  (:require
   [systems.thoughtfull.assay.dependencies.core :as core]))

(def default-rules
  "Dependency rules used when no configuration overrides them."
  core/default-rules)

(defn analyze
  "Add dependency metrics to measurements (from the metrics component),
  given the workspace's settings, a map of :top-namespace and
  :interface-ns. Returns a map of :bricks (measurements with :afferent,
  :efferent, :instability, and :abstractness added to :metrics)
  and :edges, a vector of {:from brick-name :to brick-name :interface name
  :location {:file :line}}."
  [workspace measurements]
  (core/analyze workspace measurements))

(defn check
  "Violations of dependency rules (merged over default-rules) in an
  analysis from analyze."
  [rules analysis]
  (core/check (merge default-rules rules) analysis))
