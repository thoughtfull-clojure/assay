# Changelog

This file records notable changes to assay.

The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and assay follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- The HTML report's dependency graph sits in a taller, resizable frame
  with pan, zoom, fit, and full screen controls, so large workspaces stay
  readable.

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
- Brick metrics: files, forms, functions, and mean / max function
  complexity.
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
