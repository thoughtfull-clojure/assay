# CLAUDE.md

This file guides Claude Code when it works in this repository.

## Project

`assay` measures the bricks of a Polylith workspace (form counts,
cyclomatic complexity, nesting depth, and so on), checks them against
thresholds, and reports as HTML, GitHub Actions output, or text. It can
compare with a base revision so CI and Git hooks fail only on new
violations. See `README.md` for user-facing behavior.

assay is itself a Polylith workspace. The top namespace is
`systems.thoughtfull.assay`, and each brick's public API is its
`interface` namespace. Bricks must call each other only through
interfaces; `poly check` enforces this.

- `workspace`: finds bricks and their source files.
- `parse`: parses source with rewrite-clj, so metrics see code as
  written: comments, reader macros, and all.
- `metrics`: per-function and per-brick measurements, and each source
  file's namespace and requires. To add a metric, compute it in `function`
  or `measure-brick` and add it to `function-metrics` or `metrics`.
- `dependencies`: the brick dependency graph from requires; afferent,
  efferent, instability, and abstractness (interface ratio); cohesion,
  clusters, and unused interface from resolved symbol references (the
  `cohesion` namespace); connascence of position, meaning, and algorithm
  (the `connascence` namespace); and the dependency rule checks.
- `thresholds`: brick and function rules (`:max`, `:min`, and `:std-devs`
  for bricks). To add a rule type, add a method to `evaluate`.
- `baseline`: compares with a base report, setting each violation's
  status (new, existing, or indirect).
- `git`: merge-base, changed files, and extracting a revision's tree.
- `html-report`, `github-report`, `text-report`: render a report map.
- `cli` (base): parses options and wires the components together.

Add new bricks to `deps.edn` (`:dev` and `:test`),
`projects/assay/deps.edn`, `tests.edn`, and `bb.edn`.

## Babashka compatibility

Git hooks and CI run assay under babashka for ~0.1 s startup, so brick
source must stay babashka-compatible: no `gen-class`, no `defrecord` or
`deftype` that implements Java interfaces, and only the Java classes
babashka includes. `bb test` runs every test under babashka; run it as
well as the JVM tests.

## Commands

Run these inside `devenv shell`:

```sh
test                            # JVM tests (kaocha)
test --focus <ns-or-var>        # run some tests
bb test                         # tests under babashka
poly check                      # validate the workspace
lint                            # lint (clj-kondo)
fmt                             # format (cljfmt)
bb assay --format text          # run assay on itself
devenv test                     # poly check, tests, and all Git hooks
bb sample-report                # regenerate the sample reports in docs/
clojure -T:build jar            # build the Clojars jar (see build.clj)
```

## Git hooks

devenv installs the hooks when the shell starts (see `devenv.nix`):

- `assay` runs `bb assay --format text --base HEAD` when brick source
  changes, failing on new error-level violations.
- `cljfmt` and `clj-kondo` on Clojure and EDN files.
- `vale` with the Google style on Markdown files (config in `.config/vale.ini`).
- `gitlint` on commit messages (config in `.config/gitlint`): a title of at most
  50 characters, body lines of at most 72 characters, and a non-empty body.
- `typos` on all text files, so code, comments, and docstrings get a spelling
  check too (config in `.config/typos.toml`). Add project words to
  `[default.extend-words]` there.
- `markdownlint` (config in `.config/markdownlint.yaml`, 80-column prose) and
  `lychee` link checking (config in `.config/lychee.toml`) on Markdown files.
  lychee needs network access.
- `nixfmt`, `deadnix`, and `statix` on Nix files.
- `shellcheck` and `actionlint` on shell scripts and GitHub workflows.
- Hygiene checks: merge conflict markers, private keys, large files, broken
  symlinks, shebangs, trailing whitespace, and final newlines. The fixers skip
  the vendored Google style and `devenv.lock`.

## Formatting style

Code uses a fixed 2-space body indent for every form, never aligned to the
first argument, per [Tonsky's Clojure formatting
recommendations](https://tonsky.me/blog/clojurefmt/). `.config/cljfmt.edn`
configures this. Don't "fix" indentation to align with arguments.

## Tool configuration

Tool config lives in `.config/`, not the repository root: `cljfmt.edn`,
`clj-kondo/`, `vale.ini`, `vale/`, `gitlint`, `typos.toml`,
`markdownlint.yaml`, `lychee.toml`, and `assay.edn`. Tools don't look
there by default, so always pass the path. The `lint` and `fmt` scripts
and the Git hooks already do. When you call a tool directly, use
`--config .config/cljfmt.edn`, `--config-dir .config/clj-kondo`, and so
on. Only files that must sit at the root stay there: `deps.edn`,
`workspace.edn`, `tests.edn` (kaocha), `bb.edn`, `devenv.*`, and
`.gitignore`.

That layout is this repository's preference, not assay's: assay's default
config is `assay.edn` at the workspace root. This repository passes
`--config .config/assay.edn` in the `bb assay` task and the `:run` alias,
so the hook, CI, and `bb sample-report` all use it.

Vale styles live in `.config/vale/` (the `StylesPath`). The directory
holds a copy of the Google style from errata-ai/Google v0.7.1. To update
it, copy a newer release over `.config/vale/Google/`. Add project jargon
that fails the spelling check to
`.config/vale/config/vocabularies/Assay/accept.txt`.

## Packaging

`build.clj` builds the `systems.thoughtfull/assay` jar from
`projects/assay`, putting every brick's source into it. When a brick
gains a library dependency, check that `clojure -T:build jar` lists it in
the jar's POM. The README's Releasing section has the release steps.
`docs/setup.md` documents using assay from another workspace; keep its
examples in step with the CLI and the CI workflow.
