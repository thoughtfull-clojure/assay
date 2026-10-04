# assay

Assay measures the bricks of a [Polylith](https://polylith.gitbook.io)
workspace and checks the results against thresholds. It reports as an HTML
page, as GitHub Actions annotations and a job summary, or as plain text for
a Git hook.

## Metrics

Assay measures each component and base from the Clojure files under its
`src` directory.

| Metric | Meaning |
| --- | --- |
| Files | Clojure source files |
| Lines | Lines of code, excluding blank and comment-only lines |
| Top-level forms | Forms at the top level of each file |
| Forms | Forms at any depth: collections, symbols, and literals |
| Functions | `defn`, `defn-`, `defmacro`, and `defmethod` forms |
| Cyclomatic complexity | Sum of the complexity of every function |
| Max function complexity | Complexity of the most complex function |
| Max nesting depth | Deepest nesting of collections in a top-level form |

A function's cyclomatic complexity is 1 plus its decision points, such as
`if`, `when`, each `cond` and `case` clause, each extra argument to `and`
and `or`, each `catch`, and each extra arity. The `metrics` component
documents the full rules.

## Usage

Run assay from the workspace root:

```sh
bb assay                    # write target/assay/index.html
bb assay --format text      # print violations
bb assay --base origin/main # compare with the branch you will merge into
bb assay --help
```

Under the JVM, use `clojure -M:dev:run` in place of `bb assay`.

The output format defaults to `github` under GitHub Actions (when
`GITHUB_ACTIONS` has a value), and to `html` otherwise. Pass `--format` more
than once to write several formats.

Assay exits with status 1 when a brick exceeds an error-level threshold,
and with status 2 for usage errors.

### Comparing with a base

With `--base REF`, assay also measures the merge-base of `REF` and `HEAD`,
and the files changed since then, including uncommitted ones. It marks
each violation with a status:

- **new**: introduced in a brick that changed.
- **existing**: also present at the merge-base.
- **indirect**: new, but in a brick that didn't change. A statistical
  threshold can move when other bricks change.

With a base, assay fails only on new error-level violations, so a pull
request is not blocked by problems it didn't introduce. Use
`--fail-on all` to fail on every error-level violation.

## Configuration

Assay reads `.config/assay.edn` in the workspace, or the file you pass to
`--config`:

```clojure
{:thresholds {:max-function-complexity [{:rule :max :value 10}]
              :cyclomatic-complexity [{:rule :std-devs :value 2
                                       :level :warning}]}
 :changes {:lines [{:rule :max-increase-percent :value 50}]}}
```

`:thresholds` rules apply to each brick's metrics:

- `{:rule :max :value n}`: the metric must be at most `n`.
- `{:rule :min :value n}`: the metric must be at least `n`.
- `{:rule :std-devs :value k}`: the metric must be at most `k` standard
  deviations above the mean of the other bricks of the same type. Assay
  compares components with components and bases with bases, and skips the
  rule when there are fewer than `:min-peers` other bricks (default 3).

`:changes` rules apply only with `--base`, to the bricks that changed:

- `{:rule :max-increase :value n}`: the metric may grow by at most `n`.
- `{:rule :max-increase-percent :value p}`: the metric may grow by at most
  `p` percent.

Every rule takes an optional `:level` of `:error`, the default, or
`:warning`. Configured `:thresholds` replace the defaults metric by metric,
and an empty vector turns a metric's rules off. The `thresholds` component
lists the defaults.

## Continuous integration

`.github/workflows/ci.yml` runs assay on every pull request with
`--base origin/<target branch>`. Assay annotates the changed lines, writes
a job summary, and uploads the HTML report as an artifact. The checkout
needs `fetch-depth: 0` so assay can find the merge-base.

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
```

Tool configuration lives in `.config/`.

## License

> Copyright © technosophist
>
> This Source Code Form is subject to the terms of the Mozilla Public
> License, v. 2.0. If a copy of the MPL was not distributed with this
> file, You can obtain one at <https://mozilla.org/MPL/2.0/>.
