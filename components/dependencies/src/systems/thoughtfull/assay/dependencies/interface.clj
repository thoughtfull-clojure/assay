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

  Rules map a check to a level (:error or :warning), or to nil to turn it
  off. They come in groups, each a config key named for the report section
  the rule belongs to. :dependency-rules:

  - :stable-dependencies flags a dependency on a less stable brick (the
    Stable Dependencies Principle).

  :io-rules:

  - :mutable-state flags top-level atoms, refs, agents, volatiles, and
    dynamic vars, and alter-var-root calls, in components.

  :error-handling-rules:

  - :broad-catch flags catch clauses for Exception, RuntimeException,
    Throwable, or Object in components.

  :test-rules:

  - :test-boundary flags a require, in a brick's tests, of another brick's
    namespace other than its interface.

  Five dependency rules take settings as a map with :level:

  - :connascence-of-position {:max n} flags interface functions that other
    bricks call with more than n positional parameters.
  - :duplicate-code {:min-forms n} flags code of at least n forms that
    appears in more than one brick (connascence of algorithm).
  - :merge-candidates {:max-size r} flags a component whose only dependent
    is another component, when its forms are at most r times the
    dependent's.
  - :co-change {:since s :min-shared n :min-strength r
    :max-bricks-per-commit m} flags two bricks with no dependency path
    between them that changed together in at least n commits since s,
    and in at least r of the less changed brick's commits. Commits that
    touch more than m bricks don't count. check reads the commits from the
    analysis's :commits, each a set of changed paths, and skips the rule
    without them.
  - :library-spread {:max-bricks n} flags each require of a library outside
    the workspace that more than n bricks require."
  (:require
   [systems.thoughtfull.assay.dependencies.core :as core]))

(def default-rules
  "Rules used when no configuration overrides them: a map of each group's
  config key (:dependency-rules, :io-rules, :error-handling-rules, and
  :test-rules) to its rules."
  core/default-rules)

(def rule-group-sections
  "Each rule group's config key and the report section (a key of
  metrics/sections) its rules belong to, in the order of the sections."
  core/rule-group-sections)

(defn analyze
  "Add dependency metrics to measurements (from the metrics component),
  given the workspace's settings, a map of :top-namespace and
  :interface-ns. Returns a map of :bricks (measurements with :afferent,
  :efferent, :instability, :abstractness, :cohesion, :shared-keywords,
  :libraries, :shared-libraries, :error-surface, and :untested-interface
  added to :metrics), :libraries (each
  library outside the workspace as {:library :bricks :requires}, most
  spread first), :edges (a vector of {:from brick-name :to brick-name
  :interface name :location {:file :line}}), and what check needs:
  :workspace and :used."
  [workspace measurements]
  (core/analyze workspace measurements))

(defn check
  "Violations of rules in an analysis from analyze, with :commits added for
  the :co-change rule. rules maps any group's rules to their settings,
  merged over every group's defaults, as from (apply merge (vals
  (merge-rules config))). Each violation has the :section of its rule's
  group."
  [rules analysis]
  (core/check (core/merge-flat-rules rules) analysis))

(defn merge-rules
  "Merge each group of rules in config over its defaults, returning a map
  shaped like default-rules. A map-valued rule merges key by key. Throws
  for a rule configured under the wrong group, such as :broad-catch under
  :dependency-rules."
  [config]
  (core/merge-rules config))

(defn neighbors
  "For each brick, in order: a map of :brick, :depends-on, and
  :depended-on-by, the sorted names of the bricks on each side of its
  edges."
  [bricks edges]
  (core/neighbors bricks edges))

(defn mermaid
  "A Mermaid flowchart of the brick graph, top-down. Bases have rounded
  ends. Edges in a stable-dependencies violation are red, co-change
  violations add dotted amber lines without arrows, and edges marked :new?
  (by comparing with a base) are dashed."
  [bricks edges violations]
  (core/mermaid bricks edges violations))
