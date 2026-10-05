(ns systems.thoughtfull.assay.dependencies.interface
  "Brick dependency metrics from namespace requires, after Robert Martin's
  package metrics, adapted to Polylith: a brick depends on an interface, and
  so on every component that implements it.

  - Afferent (Ca): bricks that depend on this brick's interface.
  - Efferent (Ce): interfaces this brick depends on.
  - Instability: Ce / (Ca + Ce), undefined for a brick with neither.
  - Abstractness: 1 - interface definitions / all definitions. A small
    interface over a large implementation is abstract; bases are 0.
  - Cohesion: references to the brick's own namespaces / references to any
    workspace namespace.
  - Shared keywords: keywords this brick uses that another brick also
    uses, usually map keys the bricks must agree on (connascence of
    meaning).

  Dependency rules map a check to a level (:error or :warning), or to nil to
  turn it off:

  - :stable-dependencies flags a dependency on a less stable brick (the
    Stable Dependencies Principle).
  - :new-dependencies flags a dependency that is not in the base, when
    comparing with one (applied by the baseline component).

  Three rules take settings as a map with :level:

  - :connascence-of-position {:max n} flags interface functions that other
    bricks call with more than n positional parameters.
  - :duplicate-code {:min-forms n} flags code of at least n forms that
    appears in more than one brick (connascence of algorithm).
  - :merge-candidates {:max-size r} flags a component whose only dependent
    is another component, when its forms are at most r times the
    dependent's."
  (:require
   [systems.thoughtfull.assay.dependencies.core :as core]))

(def default-rules
  "Dependency rules used when no configuration overrides them."
  core/default-rules)

(defn analyze
  "Add dependency metrics to measurements (from the metrics component),
  given the workspace's settings, a map of :top-namespace and
  :interface-ns. Returns a map of :bricks (measurements with :afferent,
  :efferent, :instability, :abstractness, :cohesion, and :shared-keywords
  added to :metrics), :edges (a vector of {:from brick-name :to brick-name
  :interface name :location {:file :line}}), and what check needs:
  :workspace and :used."
  [workspace measurements]
  (core/analyze workspace measurements))

(defn check
  "Violations of dependency rules (merged over default-rules) in an
  analysis from analyze."
  [rules analysis]
  (core/check (core/merge-rules rules) analysis))

(defn merge-rules
  "Merge configured dependency rules over default-rules. A map-valued rule
  merges key by key."
  [rules]
  (core/merge-rules rules))

(defn neighbors
  "For each brick, in order: a map of :brick, :depends-on, and
  :depended-on-by, the sorted names of the bricks on each side of its
  edges."
  [bricks edges]
  (core/neighbors bricks edges))

(defn mermaid
  "A Mermaid flowchart of the brick graph, top-down. Bases have rounded
  ends. Edges in a stable-dependencies violation are red, and new
  dependencies (from comparing with a base) are dashed."
  [bricks edges violations]
  (core/mermaid bricks edges violations))
