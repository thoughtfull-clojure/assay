# assay

Assay measures the bricks of a [Polylith](https://polylith.gitbook.io)
workspace and checks the results against thresholds. It reports as an HTML
page, as GitHub Actions annotations and a job summary, or as plain text for
a Git hook.

To add assay to a workspace as a command, a Git hook, and a GitHub Actions
job, see [Setting up assay](docs/setup.md). The
[sample report](docs/sample-report.html) is assay's report on itself;
download it and open it in a browser to view it. To learn why the metrics
matter and what to do about them, see [Using assay's metrics](docs/metrics.md).

## Metrics

Assay measures each component and base from the Clojure files under its
`src` directory, at three levels, and its tests from the files under its
`test` directory.

### Functions

Each `defn`, `defn-`, `defmacro`, and `defmethod` gets its own metrics, and
assay reports every function that breaks a rule, with its location.

| Metric | Meaning |
| --- | --- |
| Complexity | Cyclomatic complexity |
| Nesting depth | Deepest nesting in the function body |
| Forms | Forms in the function: collections, symbols, and literals |
| Parameters | Positional parameters of the widest arity |

A function's cyclomatic complexity is 1 plus its decision points, such as
`if`, `when`, each `cond` clause, each extra argument to `and` and `or`,
each `catch`, and each extra arity. A `case` counts once however many
clauses it has, since it dispatches on constants like a lookup table; only
decisions inside its bodies add more.

Nesting depth counts nested collections, except that binding values in
`let`, `loop`, `for`, and similar forms start again at depth 1. Binding
vectors, destructuring, parameter vectors, docstrings, and attribute maps
add nothing.

### Bricks

| Metric | Meaning |
| --- | --- |
| Files | Clojure source files |
| Forms | Forms in all files |
| Functions | Function definitions |
| Mean function complexity | Mean complexity of the brick's functions |
| Mean nesting depth | Mean nesting depth of the brick's functions |

The function rules catch the worst functions, so brick metrics describe
the brick as a whole.

### Dependencies

Assay builds the dependency graph between bricks from `ns` requires. A
brick depends on an interface, and so on every component that implements
it.

Both reports draw the graph with Mermaid. In the HTML report, drag to pan,
hold Ctrl or ⌘ and scroll (or pinch) to zoom, use the buttons to zoom, fit
the whole graph, or go full screen, and drag the frame's corner to make it
taller. Red edges depend on a less
stable brick, and with `--base`, dashed edges are new. Dotted amber lines
without arrows join bricks that keep changing together in Git history
although neither depends on the other: coupling the source doesn't show.
The graph draws these lines without `--warnings` too, though the
violations they stand for are warnings.
The HTML report loads Mermaid from a CDN; offline, it shows the same
dependencies as a table.

| Metric | Meaning |
| --- | --- |
| Afferent (Ca) | Bricks that depend on this brick |
| Efferent (Ce) | Interfaces this brick depends on |
| Instability | `Ce / (Ca + Ce)`: `0` is stable, `1` is unstable |
| Abstractness | `1 - interface definitions / all definitions`; bases are `0` |

Abstractness measures how much a component's interface hides: a small
interface over a large implementation is abstract. It counts definitions
(`def`, `defn`, `defmethod`, and the like) rather than forms, since an
interface function is short even when it exposes a lot. At `0.5`, each
interface definition hides only one implementation definition.

### Cohesion

Assay resolves the symbols each definition refers to through its
namespace's aliases and refers.

| Metric | Meaning |
| --- | --- |
| Cohesion | `own references / workspace references`; libraries don't count |
| Shared keywords | Keywords that another brick also uses (see connascence) |

Low cohesion means a brick is mostly glue between other bricks.

### Connascence

Connascence is what two pieces of code must agree on, so that changing
one means changing the other. Within a brick it's expected; between
bricks it's coupling. Assay checks the kinds it can read from source:

- **Position**: an interface function that other bricks call with many
  positional parameters. Every caller depends on their order.
- **Meaning**: the Shared keywords metric counts the keywords a brick uses
  that another brick also uses, usually map keys both must agree on. It
  isn't checked by default, since passing maps between bricks is normal.
- **Algorithm**: the same code in more than one brick, ignoring layout
  and comments. Assay reports the largest duplicated form, not every form
  inside it.

Assay can't see the runtime kinds, such as execution order and timing,
from source.

### I/O and mutability metrics

| Metric | Meaning |
| --- | --- |
| Libraries | Libraries outside the workspace that the brick requires |
| Shared libraries | Of those, libraries that another brick also requires |
| Host interop | Java method calls, field access, constructors, and static members |
| Interop density | Host interop forms per 100 forms |
| Mutable state | Top-level atoms, refs, agents, volatiles, dynamic vars, and `alter-var-root` calls |

Assay names a library by its namespaces: `next.jdbc` and `next.jdbc.sql`
are `next.jdbc`, and `rewrite-clj.node` and `rewrite-clj.parser` are
`rewrite-clj`. Clojure's own pure namespaces, such as `clojure.string` and
`clojure.set`, don't count; its I/O namespaces, `clojure.java.io` and
`clojure.java.shell`, do. A library that one brick wraps can change in one
place, while one that several bricks require, such as a database driver,
points to a missing gateway component.

### Error handling metrics

| Metric | Meaning |
| --- | --- |
| Error surface | Interface definitions that can throw, even indirectly |
| Untyped errors | Throws of Java exceptions, or of `ex-info` without a `:type` key |
| Catches | `catch` clauses |
| Broad catches | `catch` clauses for `Exception`, `RuntimeException`, `Throwable`, or `Object` |

A definition can throw when its body contains `throw`, or when it refers
to a definition that can, whichever brick that definition is in. Assay doesn't
account for catching, so error surface is an upper bound. An `ex-info`
counts as typed when its literal data map has a `:type` key, in any
namespace, or `:cognitect.anomalies/category`. Rethrows, and data that
isn't a literal map, don't count either way.

### Test metrics

| Metric | Meaning |
| --- | --- |
| Tests | `deftest` forms |
| Assertions per test | Mean `is` and `are` assertions per `deftest` |
| Forms per test | Mean forms per `deftest` |
| Untested interface | Interface definitions that no test in the workspace mentions |
| Isolation hazards | `with-redefs`, `Thread/sleep`, `alter-var-root`, and top-level mutable state in tests |
| Test ratio | Test forms per source form |

A test mentions an interface definition when it refers to it through an
alias or a refer, so a test in any brick counts. Line coverage needs the
tests to run, so assay doesn't measure it.

## Usage

Run assay from the workspace root:

```sh
bb assay                    # write target/assay/index.html
bb assay --format text      # print violations
bb assay --base origin/main # compare with the branch you will merge into
bb assay --warnings         # include warning-level violations
bb assay --help
```

Under the JVM, use `clojure -M:dev:run` in place of `bb assay`.

The output format defaults to `github` under GitHub Actions (when
`GITHUB_ACTIONS` has a value), and to `html` otherwise. Pass `--format` more
than once to write several formats.

Reports list only error-level violations unless you pass `--warnings`,
and count the warnings they leave out. Warnings point to code worth
refactoring before it reaches an error, but they never fail a run.

After the violations, the HTML and GitHub reports have six sections:
dependencies (the graph, afferent and efferent coupling, and instability),
complexity (size and function complexity), modularity (abstractness,
cohesion, and shared keywords), I/O and mutability (libraries, with a
table of those more than one brick requires, host interop, and mutable
state), error handling, and tests. Each has a table of every brick, with
a row of averages across all bricks. With `--base`, each table shows only
the bricks that are new or whose values in that section changed, with
how much they changed, such as `12 (+3)`. The average row stays an
average of all bricks. The tables mark each value
2 or more standard deviations from the mean of all bricks: the HTML report
outlines it, and the GitHub report sets it in bold. Afferent coupling,
instability, and abstractness compare components only, since a base has no
interface and no dependents.

Assay exits with status 1 when a brick or function exceeds an error-level
threshold, and with status 2 for usage errors.

### Comparing with a base

With `--base REF`, assay also measures the merge-base of `REF` and `HEAD`,
and the files changed since then, including uncommitted ones. It marks
each violation with a status:

- **new**: introduced in a brick that changed. A function or dependency is
  matched by name, so a function that moves is still the same.
- **existing**: also present at the merge-base.
- **indirect**: new, but in a brick that didn't change. A statistical
  threshold can move when other bricks change.

With a base, assay fails only on new error-level violations, so a pull
request is not blocked by problems it didn't introduce, and the reports
list only new violations, with a count of the rest. Use `--fail-on all`
to fail on every error-level violation; the reports then list every
violation with its status, so they show why a run failed.

## Configuration

Assay reads `assay.edn` at the workspace root, or the file you pass to
`--config`. Every key is optional and merges over the defaults:

```clojure
{:function-thresholds {:complexity [{:rule :max :value 10}]
                       :depth [{:rule :max :value 8 :level :warning}]
                       :forms [{:rule :max :value 150 :level :warning}]
                       :params [{:rule :max :value 4 :level :warning}]}
 :brick-thresholds {:mean-function-complexity [{:rule :std-devs :value 2
                                                :level :warning}]
                    :abstractness [{:rule :min :value 0.5 :level :warning
                                    :types #{:component}}]}
 :dependency-rules {:stable-dependencies :error
                    :new-dependencies :warning
                    :connascence-of-position {:max 3 :level :warning}
                    :duplicate-code {:min-forms 30 :level :warning}
                    :merge-candidates {:max-size 0.25 :level :warning}
                    :library-spread {:max-bricks 1 :level :warning}
                    :mutable-state :warning
                    :broad-catch :warning
                    :test-boundary :warning
                    :co-change {:since "12 months" :min-shared 5
                                :min-strength 0.5 :max-bricks-per-commit 5
                                :level :warning}}
 :change-thresholds {:forms [{:rule :max-increase-percent :value 50}]}}
```

That example shows the defaults, except for `:change-thresholds`, which has
none.

Threshold rules:

- `{:rule :max :value n}`: the metric must be at most `n`.
- `{:rule :min :value n}`: the metric must be at least `n`.
- `{:rule :std-devs :value k}`: bricks only. The metric must be at most `k`
  standard deviations above the mean of the other bricks of the same type.
  Assay compares components with components and bases with bases, and
  skips the rule when there are fewer than `:min-peers` other bricks
  (default 3).

Every rule takes an optional `:level` of `:error`, the default, or
`:warning`. Brick rules take an optional `:types`, such as
`#{:component}`. A configured metric replaces its default rules, and an
empty vector turns them off.

Dependency rules set a level, or `nil` to turn a check off:

- `:stable-dependencies`: a brick depends on a less stable brick, which
  breaks the Stable Dependencies Principle.
- `:new-dependencies`: with `--base`, a dependency between bricks that the
  base didn't have.
- `:mutable-state`: a top-level atom, ref, agent, volatile, or dynamic var,
  or an `alter-var-root` call, in a component. Bases are the imperative
  shell, so they don't count.
- `:broad-catch`: a `catch` clause for `Exception`, `RuntimeException`,
  `Throwable`, or `Object` in a component. Bases, at the edges, are where
  catching belongs.
- `:test-boundary`: a test that requires another brick's namespace other
  than its interface. Tests that reach into an implementation break when
  it changes, even though its interface didn't.
- `:connascence-of-position`, with `:max`: an interface function that other
  bricks call has more than `:max` positional parameters.
- `:duplicate-code`, with `:min-forms`: code of at least `:min-forms` forms
  appears in more than one brick.
- `:merge-candidates`, with `:max-size`: a component whose only dependent
  is another component, and whose forms are at most `:max-size` times that
  component's. A component that only a base uses doesn't count, since
  Polylith keeps logic out of bases.
- `:co-change`: two bricks with no dependency path between them, in either
  direction, that changed together in Git history. Assay reads the commits
  since `:since` (any date `git log --since` accepts), leaving out merges
  and commits that touch more than `:max-bricks-per-commit` bricks, such as
  a reformat. A pair counts when it shares at least `:min-shared` commits,
  and at least `:min-strength` of the less changed brick's commits. With
  `--base`, these violations are always existing, since they come from
  history rather than from the change. Without Git history, assay skips
  the check.
- `:library-spread`, with `:max-bricks`: a library that more than
  `:max-bricks` bricks require, reported at each brick's require.

These five take a map with a `:level`. A configured map merges over the
default, so `{:duplicate-code {:min-forms 50}}` keeps the default level.

`:change-thresholds` apply only with `--base`, to the bricks that changed:

- `{:rule :max-increase :value n}`: the metric may grow by at most `n`.
- `{:rule :max-increase-percent :value p}`: the metric may grow by at most
  `p` percent.

## Continuous integration

`.github/workflows/ci.yml` runs assay on every pull request and push to
`main`. Assay annotates the code, writes a job summary, and uploads the
HTML report as an artifact.

- **Pull requests** compare with `origin/<target branch>` and fail only on
  new error-level violations, so a pull request isn't blocked by problems
  it didn't introduce.
- **Pushes to `main`** compare with the commit before the push and fail
  only on new error-level violations, so the summary shows what the push
  changed. When that commit is missing, such as after a force push, assay
  has no base and fails on any error-level violation.

The checkout needs `fetch-depth: 0` so assay can find earlier commits.

## Git hook

The `assay` pre-commit hook runs `bb assay --format text --base HEAD`, which
compares the working tree with `HEAD`. It runs under babashka in about a
tenth of a second.

## Development

The development environment uses [devenv](https://devenv.sh). Run
`devenv shell` to enter it. The shell provides a JDK, the Clojure CLI,
babashka, `poly`, and the linters that the Git hooks run.

```sh
test        # JVM tests (kaocha)
bb test     # the same tests under babashka
poly check  # validate the Polylith workspace
lint        # lint (clj-kondo)
fmt         # format (cljfmt)
assay       # run assay on itself under the JVM
devenv test # poly check, tests, and every Git hook
bb sample-report # regenerate docs/sample-report.html
```

Tool configuration lives in `.config/`.

### Releasing

Assay publishes to Clojars as `systems.thoughtfull/assay`, built from the
`projects/assay` project. `build.edn` holds the version, and
`build.clj` puts every brick's source into one jar.

1. Set the version in `build.edn`, and move the `Unreleased` entries in
   `CHANGELOG.md` under a heading for that version and date.
2. Update the `:mvn/version` in `docs/setup.md`.
3. Run `clojure -T:build jar` and check the jar, or `clojure -T:build
   install` to try it from `~/.m2`.
4. Commit, tag the commit `v<version>`, and push both.
5. Run `clojure -T:build deploy`. It reads Clojars credentials from
   `~/.clojars.edn`: `{:username "..." :password "<deploy token>"}`.

## License

> Copyright © technosophist
>
> This Source Code Form is subject to the terms of the Mozilla Public
> License, v. 2.0. If a copy of the MPL was not distributed with this
> file, You can obtain one at <https://mozilla.org/MPL/2.0/>.
