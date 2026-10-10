(ns systems.thoughtfull.assay.dependencies.interface
  "Brick dependency metrics from namespace requires, after Robert Martin's
  package metrics, adapted to Polylith: a brick depends on an interface, and
  so on every component that implements it.

  - Afferent (Ca): bricks that depend on this brick's interface.
  - Efferent (Ce): interfaces this brick depends on.
  - Instability: Ce / (Ca + Ce), undefined for a brick with neither.
  - Abstractness: 1 - interface definitions / all definitions. A small
    interface over a large implementation is abstract; bases have none.
  - Cohesion: references to the brick's own namespaces / references to any
    workspace namespace.
  - Shared keywords: keywords this brick uses that another brick also
    uses, usually map keys the bricks must agree on (connascence of
    meaning).
  - Libraries: libraries outside the workspace the brick requires, and
    shared libraries, those another brick also requires.
  - Error surface: a component's interface definitions that can throw,
    directly or through what they refer to.
  - Untested interface: a component's interface definitions that no test
    in the workspace mentions.
  - Main-sequence distance: |abstractness + instability - 1| for a
    component.

  findings collects what the threshold component checks: the findings of
  each metric of :finding and :count kind (see metrics/metrics), each
  with the :brick it belongs to and what reports show of it."
  (:require
   [systems.thoughtfull.assay.dependencies.core :as core]))

(defn analyze
  "Add dependency metrics to measurements (from the metrics component),
  given the workspace's settings, a map of :top-namespace and
  :interface-ns. Returns a map of :bricks (measurements with :afferent,
  :efferent, :instability, :abstractness, :cohesion, :shared-keywords,
  :libraries, :shared-libraries, :error-surface, :untested-interface, and
  :main-sequence-distance added to :metrics), :libraries (each
  library outside the workspace as {:library :bricks :requires}, most
  spread first), :edges (a vector of {:from brick-name :to brick-name
  :interface name :location {:file :line}}), and what findings
  needs: :workspace and :used."
  [workspace measurements]
  (core/analyze workspace measurements))

(defn findings
  "The findings of each metric of :finding and :count kind, as a map of
  metric key to a vector of findings, each a map of :brick and optionally
  :value (what a threshold compares), :subject (what identifies it across
  revisions), :location, :message, and :historical?. settings maps a
  metric key to its merged settings (as from the thresholds component),
  for the options that shape findings: :allow for :library-spread, the
  lowest limit of :duplicate-code (smaller duplicates aren't collected),
  and :min-shared and :max-bricks-per-commit for :co-change, which reads
  the commits from analysis's :commits (each a set of changed paths) and
  is left out without settings."
  [settings analysis]
  (core/findings settings analysis))

(defn neighbors
  "For each brick, in order: a map of :brick, :depends-on, and
  :depended-on-by, the sorted names of the bricks on each side of its
  edges."
  [bricks edges]
  (core/neighbors bricks edges))

(defn mermaid
  "A Mermaid flowchart of the brick graph, top-down. Bases have rounded
  ends. Edges in an unstable-dependencies violation are red, co-change
  violations add dotted amber lines without arrows, and edges marked :new?
  (by comparing with a base) are dashed."
  [bricks edges violations]
  (core/mermaid bricks edges violations))
