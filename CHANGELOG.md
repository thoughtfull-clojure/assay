# Changelog

This file records notable changes to assay.

The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and assay follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- The default config file is `assay.edn` at the workspace root, not
  `.config/assay.edn`. Pass `--config` to use another location.

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
