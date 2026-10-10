# assay

Assay measures the bricks of a [Polylith](https://polylith.gitbook.io)
workspace and checks the results against thresholds. It reports as an HTML
page, as GitHub Actions annotations and a job summary, or as plain text for
a Git hook.

To add assay to a workspace as a command, a Git hook, and a GitHub Actions
job, see [Setting up assay](docs/setup.md). The
[sample report](docs/sample-report.html) is assay's report on itself;
download it and open it in a browser to view it. The
[sample GitHub summary](docs/sample-github-summary.md) is the job summary
the same run writes under GitHub Actions, and GitHub shows it as it
would in a workflow run. To learn why the metrics
matter and what to do about them, see [Using assay's metrics](docs/metrics.md).

## Metrics

Assay measures each component and base from the Clojure files under its
`src` directory, and its tests from the files under its `test` directory.
The metrics fall into six categories. Each is a section of the report and
a key of the [configuration](#configuration), and the tables below list
each metric's default thresholds. A metric with no default is context: it
shows in the report, and you can give it thresholds.

### Dependencies

Assay builds the dependency graph between bricks from `ns` requires. A
brick depends on an interface, and so on every component that implements
it.

| Metric | Meaning | Default |
| --- | --- | --- |
| Afferent (Ca) | Bricks that depend on this component | |
| Efferent (Ce) | Interfaces this brick depends on | |
| Instability | `Ce / (Ca + Ce)`: `0` is stable, `1` is unstable | |
| Unstable dependencies | Dependencies on a brick whose instability is higher by more than the threshold | error > 0.1 |
| Positional interface | Interface functions others call with more positional parameters than the threshold | warning > 4 |
| Co-change | Bricks without a dependency between them that changed together in more than the threshold of this brick's commits | warning > 0.5 |

Both reports draw the graph with Mermaid. In the HTML report, drag to pan,
hold Ctrl or ⌘ and scroll (or pinch) to zoom, use the buttons to zoom, fit
the whole graph, or go full screen, and drag the frame's corner to make it
taller. Red edges are unstable dependencies, and with `--base`, dashed
edges are new. Dotted amber lines without arrows join bricks that keep
changing together in Git history although neither depends on the other:
coupling the source doesn't show. The graph draws these lines without
`--warnings` too, though the violations they stand for are warnings. The
HTML report loads Mermaid from a CDN; offline, it shows the same
dependencies as a table.

Nothing can depend on a base, so a base has no afferent coupling, and its
instability is always `1`; its cells show a dash. Co-change reads the
commits since `:since` (default `"12 months"`, any date `git log --since`
accepts), leaving out merges and commits that touch more than
`:max-bricks-per-commit` bricks (default 5), such as a reformat. A pair
counts when it shares at least `:min-shared` commits (default 5). With
`--base`, co-change violations are always existing, since they come from
history rather than from the change. Without Git history, assay skips it.

### Complexity

| Metric | Meaning | Default |
| --- | --- | --- |
| Forms | Forms in all files: collections, symbols, and literals | |
| Functions | `defn`, `defn-`, `defmacro`, and `defmethod` definitions | |
| Mean function complexity | Mean cyclomatic complexity of the brick's functions | |
| Mean nesting depth | Mean nesting depth of the brick's functions | |
| Complex functions | Functions with cyclomatic complexity above the threshold | error > 10 |
| Deep functions | Functions nested deeper than the threshold | warning > 8 |
| Long functions | Functions of more forms than the threshold | warning > 150 |
| Many-parameter functions | Functions with more positional parameters, in their widest arity, than the threshold | warning > 4 |

The four function metrics check each function, and a brick's cell counts
its functions past the threshold. Assay lists each such function once,
with every limit it's past, at its definition.

A function's cyclomatic complexity is 1 plus its decision points, such as
`if`, `when`, each `cond` clause, each extra argument to `and` and `or`,
each `catch`, and each extra arity. A `case` counts once however many
clauses it has, since it dispatches on constants like a lookup table; only
decisions inside its bodies add more.

Nesting depth counts nested collections, except that binding values in
`let`, `loop`, `for`, and similar forms start again at depth 1. Binding
vectors, destructuring, parameter vectors, docstrings, attribute maps, and
reader macros that wrap a form, such as `@` and `'`, add nothing.

The means compare only bricks with at least 5 functions.

### Modularity

| Metric | Meaning | Default |
| --- | --- | --- |
| Abstractness | `1 - public interface definitions / all definitions` | |
| Main-sequence distance | `\|abstractness + instability - 1\|` | warning > 0.7 |
| Cohesion | `own references / workspace references`; libraries don't count | warning < 0.5 |
| Shared keywords | Qualified keywords that another brick also uses | |
| Duplicated forms | Forms in code, of more forms than the threshold, that another brick also has | warning > 30 |
| Merge into | A component's only dependent, itself a component, and its size as a share of that one's | warning < 0.25 |

Abstractness measures how much a component's interface hides. It counts
definitions (`def`, `defn`, `defmethod`, and the like) rather than forms,
since an interface function is short even when it exposes a lot, and
leaves out private definitions in an interface namespace. Main-sequence
distance weighs it against instability: a stable component should be
abstract, and an unstable one needn't be. It checks components with at
least 10 definitions, and its tooltip says which side of the main
sequence a component is on.

Cohesion resolves the symbols each definition refers to through its
namespace's aliases and refers. Low cohesion means a component is mostly
glue between other bricks. Bases are glue by design, so they have none.
It checks components with at least 10 workspace references.

Duplicated forms compares code after formatting and comments, and counts
the largest duplicated form, not every form inside it. A merge candidate
counts only when its dependent is a component, since Polylith keeps logic
out of bases.

### I/O and mutability metrics

| Metric | Meaning | Default |
| --- | --- | --- |
| Libraries | Libraries outside the workspace that the brick requires | |
| Spread libraries | Of those, libraries that more bricks than the threshold require | warning > 3 |
| Interop density | Java interop forms per 100 forms: method calls, field access, constructors, and static members | warning > 5 (components) |
| Mutable state | Top-level atoms, refs, agents, volatiles, dynamic vars, and `alter-var-root` calls | warning > 0 (components) |

Assay names a library by its namespaces: `next.jdbc` and `next.jdbc.sql`
are `next.jdbc`, and `rewrite-clj.node` and `rewrite-clj.parser` are
`rewrite-clj`. A reverse-domain name keeps its organization and library,
so `systems.thoughtfull.amalgam` and `systems.thoughtfull.desiderata` are
two libraries. Clojure's own pure namespaces, such as `clojure.string` and
`clojure.set`, don't count; its I/O namespaces, `clojure.java.io` and
`clojure.java.shell`, do. Libraries in the `:allow` setting of
`:library-spread`, such as a logging library, don't count toward it. The
shared libraries table marks each library past the threshold.

Bases are the imperative shell, so interop density and mutable state check
components only. A base shows its value uncolored.

### Error handling

| Metric | Meaning | Default |
| --- | --- | --- |
| Error surface | Share of the public interface definitions that can throw, even indirectly | |
| Untyped errors | Throws of Java exceptions, or of `ex-info` without a `:type` key | warning > 0 (components) |
| Broad catches | `catch` clauses for `Exception`, `RuntimeException`, `Throwable`, or `Object` that don't rethrow | warning > 0 (components) |

A definition can throw when its body contains `throw` or slingshot's
`throw+`, or when it refers to a definition that can, whichever brick
that definition is in. Assay doesn't account for catching, so error
surface is an upper bound. An `ex-info`, or a `throw+` of a literal map,
counts as typed when the map has a `:type` key, in any namespace, or
`:cognitect.anomalies/category`. Rethrows, and data that isn't a literal
map, don't count either way. A broad catch that rethrows, such as
wrapping the failure in a typed `ex-info`, translates the failure rather
than hiding it, so it doesn't count; the message says whether a catch
logs and carries on, or carries on silently.

### Tests

| Metric | Meaning | Default |
| --- | --- | --- |
| Tests | `deftest` forms | |
| Assertions per test | Mean `is` and `are` assertions per `deftest` | warning > mean + 2σ (components) |
| Forms per test | Mean forms per `deftest` | warning > mean + 2σ (components) |
| Untested interface | Share of the public interface definitions that no test in the workspace mentions | warning > 0.5 |
| Boundary crossings | Requires, in tests, of another brick's source namespaces other than its interface | warning > 0 |
| Isolation hazards | `with-redefs`, `Thread/sleep`, `alter-var-root`, and top-level mutable state in tests | |
| Test ratio | Test forms per source form | |

A test mentions an interface definition when it refers to it through an
alias or a refer, so a test in any brick counts. Untested interface
checks components with at least 10 definitions, and the means per test
compare components with at least 10 tests. A require of another brick's
test namespace, such as shared generators, isn't a boundary crossing.
Line coverage needs the tests to run, so assay doesn't measure it.

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

The HTML and GitHub reports open with a summary of the violations: a
row for each metric that has any, by category, counting errors and
warnings, and linked to them. Then comes a section for each category: a
table of every brick, with a row of averages, a legend with each metric's
thresholds, and the category's violations, a collapsed group for each
metric, most severe first. Each finding is one row: a function past
several limits, with all of them; a duplicate, with both copies. The
shared libraries table, in the I/O and mutability section, lists each
library more than one brick requires and marks those spread past the
threshold. Last, a thresholds table mirrors the configuration, marks the
metrics it sets, and counts each one's violations. The text report lists
the same findings under a heading for each category.

A cell past a threshold takes the color of its level. Hover over a cell for
what explains its value, such as how many of a component's interface
definitions can throw. The tables also mark a ratio, density, or mean 2
or more standard deviations worse than the mean of the other bricks the
metric checks: the HTML report outlines it, and the GitHub report sets it
in bold. Counts aren't marked, since they grow with a brick's size.

With `--base`, the summary also lists the new violations and counts the
resolved ones, and each table shows only the bricks that are new or whose
values in that section changed, with how much they changed, such as `12
(+3)`. The average row stays an average of all bricks.

Assay exits with status 1 when a brick or function exceeds an error-level
threshold, and with status 2 for usage errors.

### Comparing with a base

With `--base REF`, assay also measures the merge-base of `REF` and `HEAD`,
and the files changed since then, including uncommitted ones. It marks
each violation with a status:

- **new**: introduced in a brick that changed. Assay matches a function
  or other finding by name, so a function that moves is still the same. A function
  violation present at the merge-base is new too when the change made it
  worse, such as a complex function that grew more complex; its message
  ends with the value at the merge-base.
- **existing**: also present at the merge-base, and no worse.
- **indirect**: new, but in a brick that didn't change. A statistical
  threshold can move when other bricks change.

With a base, assay fails only on new error-level violations, so a pull
request is not blocked by problems it didn't introduce, and the reports
list only new violations, with a count of the rest. Use `--fail-on all`
to fail on every error-level violation; the reports then list every
violation with its status, so they show why a run failed.

## Configuration

Assay reads `assay.edn` at the workspace root, or the file you pass to
`--config`. Its keys are the report's categories, each mapping metrics to
their thresholds, so the configuration reads like the report. Every key is
optional and merges over the defaults. These are the defaults:

```clojure
{:dependencies {:unstable-dependencies {:error 0.1}
                :positional-interface {:warning 4}
                :co-change {:warning 0.5 :since "12 months" :min-shared 5
                            :max-bricks-per-commit 5}}
 :complexity {:function-complexity {:error 10}
              :function-depth {:warning 8}
              :function-forms {:warning 150}
              :function-params {:warning 4}}
 :modularity {:main-sequence-distance {:warning 0.7}
              :cohesion {:warning 0.5}
              :duplicate-code {:warning 30}
              :merge-candidate {:warning 0.25}}
 :io {:library-spread {:warning 3 :allow #{}}
      :interop-density {:warning 5}
      :mutable-state {:warning 0}}
 :errors {:untyped-errors {:warning 0}
          :broad-catches {:warning 0}}
 :tests {:assertions-per-test {:warning {:std-devs 2}}
         :forms-per-test {:warning {:std-devs 2}}
         :untested-interface {:warning 0.5}
         :boundary-crossings {:warning 0}}}
```

Each metric takes a `:warning` and an `:error` threshold. A value past
both is an error. What a threshold means comes from the metric, as the
[metrics](#metrics) tables list: whether it flags values above or below
it, which brick types it checks, and what it compares, such as each
function, each finding, or a brick's own value. So `{:error 10}` for
`:function-complexity` flags each function above 10, and `{:warning 0.5}`
for `:cohesion` flags each component below 0.5.

Settings merge one by one, so
`{:complexity {:function-depth {:error 12}}}` keeps the default warning
at 8. A `nil` threshold turns that level off, and a `nil` metric turns
the metric off: `{:io {:mutable-state nil}}`. Metrics with no default,
such as `:mean-function-complexity`, take thresholds the same way.

A threshold is a number, or, for a metric of a brick's own value,
`{:std-devs k}`. That flags a brick more than `k` sample standard
deviations past the mean of the other bricks the metric checks, such as
`{:complexity {:mean-function-complexity {:warning {:std-devs 2}}}}`. It
needs at least `:min-peers` other bricks (default 3), as in
`{:std-devs 2 :min-peers 5}`, and assay skips it otherwise.

A few metrics take options too: `:allow` for `:library-spread`, a set of
library names that don't count, and `:since`, `:min-shared`, and
`:max-bricks-per-commit` for `:co-change` (see
[dependencies](#dependencies)).

An unknown category, metric, or setting, a metric under the wrong
category, or a malformed threshold is a usage error, and so is the
configuration format of assay 0.2 and earlier, with
`:function-thresholds`, `:brick-thresholds`, and `:*-rules` keys. To
convert one, find each rule's metric in the tables above, and set its
level to the old limit.

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
bb sample-report # regenerate the sample reports in docs/
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
