# Changelog

This file records notable changes to assay.

The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and assay follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- ClojureScript support. Requires of JavaScript modules, such as
  `["react" :as react]`, count as libraries named by their npm package;
  `:require-macros` and `:use-macros` count as requires; and requires in
  reader conditionals count for every platform. `js/` references count as
  interop, `(js/Error. msg)` as an untyped error, and
  `(catch :default e)`, `js/Error`, and `js/Object` as broad catches.
  ClojureScript's own namespaces and the Google Closure Library don't
  count as libraries.
- `:allow` for library spread, a set of libraries that don't count.
- Slingshot's `throw+` counts as a throw, typed by its map's `:type`.
- Cell tooltips explain values, such as how many interface definitions
  can throw, and which side of the main sequence a component is on.
- A sample GitHub job summary, `docs/sample-github-summary.md`, beside the
  sample HTML report. `bb sample-report` regenerates both.

### Changed

- **Breaking:** the configuration follows the report's layout. Its keys
  are the report's categories (`:dependencies`, `:complexity`,
  `:modularity`, `:io`, `:errors`, `:tests`), each mapping metrics to a
  `:warning` and an `:error` threshold and any options, such as
  `{:complexity {:function-depth {:warning 8 :error 12}}}`. Each metric
  knows whether it flags high or low values and which brick types it
  checks, so the configuration doesn't say. Settings merge one by one
  over the defaults, and `nil` turns a level or a metric off. Unknown
  categories, metrics, and settings, and misplaced metrics, are a usage
  error, so a configuration in the old shape fails.
- Every rule is a column, so every violation marks a cell: unstable
  dependencies, positional interface, and co-change under dependencies;
  complex, deep, long, and many-parameter functions under complexity;
  main-sequence distance, duplicated forms, and merge into under
  modularity; spread libraries under I/O; and boundary crossings under
  tests. Stable dependencies is now unstable dependencies, connascence
  of position is positional interface, broad catch is broad catches,
  test boundary is boundary crossings, and merge candidates is merge
  into.
- The reports open with a summary of each metric's violations, and list
  each category's violations under its table, one row per finding: a
  function past several limits is one row, at its definition, and a
  duplicate is one row with both copies. The text report groups its
  lines the same way. Column headers and legends give each metric's
  thresholds, and the thresholds table mirrors the configuration.
- The reports list violations most severe first: errors before
  warnings, then by how far each value is past its limit. The HTML and
  GitHub reports collapse a list's rows past the first 20.
- With `--base`, the reports show only what the change affects: each
  metric table lists only the bricks that are new or changed in that
  section, followed by the average of all bricks, and the violations list
  only new ones, with a count of those left out. With `--fail-on all`,
  every violation is still listed.
- With `--base`, a function violation that was in the base is new when
  the change made it worse, and its message says what the value was.
- CI fails pushes to `main` only on new error-level violations, as it
  does pull requests, rather than on every error-level violation.
- Defaults: unstable dependencies need an instability gap of more than
  0.1, positional interface allows 4 parameters rather than 3, library
  spread allows 3 bricks rather than 1, and main-sequence distance
  (warning above 0.7) replaces the abstractness rule. Untyped errors,
  interop density, untested interface (a share, warning above half),
  cohesion (warning below 0.5), and assertions and forms per test
  (warning 2 standard deviations above the other components) have rules
  now.
- Limits are strict: a duplicate of exactly 30 forms, or a co-change
  strength of exactly 0.5, isn't flagged.
- Abstractness, error surface, and untested interface count only public
  interface definitions; error surface and untested interface are
  shares of them. Bases have no abstractness, rather than `0`. Shared
  keywords count only qualified keywords. Cohesion measures components
  only.
- Assay checks means and shares only for bricks with enough to go on: 5
  functions for the function means, 10 tests for the means per test, 10
  definitions for main-sequence distance and untested interface, and 10
  workspace references for cohesion.
- Outlines mark only ratios, densities, and means, only when worse, and
  with the statistic `{:std-devs k}` uses: the sample standard deviation
  of the other bricks the metric checks.
- A `case` adds 1 to cyclomatic complexity, however many clauses it has,
  since it dispatches on constants in one step. Decisions in its bodies
  still count.
- A reader conditional is one form whose children are its branch values,
  for every platform, so its features no longer count as forms, nesting,
  or shared keywords.
- Broad catches leave out catches that rethrow, and say whether a catch
  logs or carries on silently. Boundary crossings leave out requires of
  another brick's test namespaces.
- The HTML report's dependency graph sits in a taller, resizable frame
  with pan, zoom, fit, and full screen controls, so large workspaces stay
  readable.

### Removed

- The files, host interop, catches, and shared libraries columns. The
  shared libraries table remains, and marks spread libraries.
- Change thresholds (`:change-thresholds`) and the new dependencies rule.
  With `--base`, the graph still dashes new dependencies.
- The default threshold for mean function complexity. The metric remains,
  and takes thresholds like any other.

### Fixed

- An `ns` form with metadata on its name, such as `(ns ^:no-doc a.b)`,
  had no namespace, so interface, cohesion, and test boundary checks left
  its file out.
- Auto-resolved keywords, `::k` and `::alias/k`, including `:as-alias`
  aliases, resolve to their namespaces in keyword counts and in the typed
  error check.
- Reader macros that wrap a form, such as `@` and `'`, no longer add
  nesting depth.
- Libraries with reverse-domain names, such as
  `systems.thoughtfull.amalgam` and `systems.thoughtfull.desiderata`, are
  no longer counted as one library.

## [0.2.0] - 2026-10-05

### Added

- Host interop, in the I/O and mutability section, counts Java method
  calls, field access, constructors, and static members. Interop density
  counts them per 100 forms.
- A tests section of the reports, from each brick's `test` directory:
  tests, assertions and forms per test, untested interface (interface
  definitions no test mentions), isolation hazards (`with-redefs`,
  `Thread/sleep`, and the like), and test ratio. The `:test-boundary`
  rule warns about a test that requires another brick's implementation.
- An error handling section of the reports, with error surface (interface
  definitions that can throw), untyped errors, catches, and broad
  catches. The `:broad-catch` rule warns about catching `Exception`,
  `Throwable`, and the like in components.
- An I/O and mutability section of the reports, with two new rules.
  `:library-spread` warns at each require of a library that more than one
  brick requires, such as a database driver used outside its gateway
  component. `:mutable-state` warns about top-level atoms, refs, agents,
  volatiles, dynamic vars, and `alter-var-root` calls in components.
- The `:co-change` dependency rule warns about two bricks that keep
  changing together in Git history although neither depends on the
  other, and the graph joins them with a dotted line.
- The `:merge-candidates` dependency rule warns about a small component
  whose only dependent is another component, since it may belong inside
  that component.
- The bricks summary marks each value 2 or more standard deviations from
  the mean of all bricks, with an outline in the HTML report and bold in
  the GitHub report. Metrics that only describe components compare
  components only.
- A guide to using assay's metrics, `docs/metrics.md`: why each metric
  matters, how to read it, and what to do when it flags something.

### Changed

- The reports group brick metrics into sections, each with its own
  table: dependencies, complexity, and modularity. With `--base`, a changed
  brick's values in those tables show how much they changed, in place of
  the Changed bricks table.
- Bricks have mean nesting depth, over their functions, in place of max
  nesting depth.
- The bricks summary ends with a row of averages across bricks, in place
  of totals.
- Abstractness counts definitions rather than forms:
  `1 - interface definitions / all definitions`. Forms made every
  component look abstract, since interface functions are short.
- The default config file is `assay.edn` at the workspace root, not
  `.config/assay.edn`. Pass `--config` to use another location.

### Removed

- The clusters and unused interface metrics, and the `:unused-interface`
  dependency rule.
- Max function complexity for bricks, since the function rules catch the
  most complex functions.
- The `:cycles` dependency rule, since `poly check` reports cycles between
  bricks.
- The Functions to review table, which listed the most complex functions
  whether or not they broke a rule. Function violations still appear among
  the violations.

## [0.1.0] - 2026-10-04

### Added

- Function metrics: cyclomatic complexity, nesting depth, size in forms,
  and positional parameters, with a violation for each function that
  breaks a rule.
- Brick metrics: files, forms, functions, mean / max function
  complexity, and max nesting depth.
- Dependency metrics from `ns` requires: afferent and efferent coupling,
  instability, and abstractness as the interface ratio.
- Dependency checks: stable dependencies, cycles, new dependencies, and
  unused interface definitions.
- Cohesion and clusters, from each definition's resolved references.
- Connascence checks between bricks: position, meaning (shared keywords),
  and algorithm (duplicate code).
- Static, statistical (`:std-devs`), and change thresholds, configured in
  `.config/assay.edn`.
- Comparison with a base revision (`--base`), failing only on new
  violations.
- HTML, GitHub Actions, and text reports, with a Mermaid dependency graph.
