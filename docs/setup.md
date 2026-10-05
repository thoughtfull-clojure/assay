# Setting up assay in a workspace

This guide adds assay to a Polylith workspace: as a command, as a Git hook
that checks each commit, and as a GitHub Actions job that checks each pull
request and each push to `main`.

Assay needs:

- A Polylith workspace: a `workspace.edn` with `:top-namespace`, and bricks
  under `components` and `bases`.
- [Babashka](https://babashka.org) runs assay fast enough for hooks and CI.
  The Clojure CLI works too, but takes a few seconds to start the JVM.
- A JDK, which Babashka and the Clojure CLI use the first time they fetch
  dependencies.

## Add assay

With Babashka, add assay and a task to the workspace's `bb.edn`:

```clojure
{:deps {systems.thoughtfull/assay {:mvn/version "0.1.0"}}
 :tasks
 {assay {:doc "Check code metrics with assay"
         :requires ([systems.thoughtfull.assay.cli.main :as assay])
         :task (apply assay/-main *command-line-args*)}}}
```

With the Clojure CLI, add an alias to `deps.edn`:

```clojure
{:aliases
 {:assay {:replace-deps {systems.thoughtfull/assay {:mvn/version "0.1.0"}}
          :main-opts ["-m" "systems.thoughtfull.assay.cli.main"]}}}
```

To use a commit that isn't on Clojars, use a Git dependency instead of
`:mvn/version`:

```clojure
io.github.thoughtfull-clojure/assay {:git/sha "<commit sha>"
                                     :deps/root "projects/assay"}
```

Then run it from the workspace root:

```sh
bb assay                  # or: clojure -M:assay
bb assay --format text    # print violations
bb assay --help
```

By default, assay writes an HTML report to `target/assay/index.html`. See
the [sample report](sample-report.html) for what it looks like.

## Configure thresholds

Assay works without configuration. To change thresholds, add
`.config/assay.edn` to the workspace. The README's
[configuration section](../README.md#configuration) lists every setting and
its default.

## Check each commit with a Git hook

The hook compares the working tree with `HEAD` and fails the commit only
on error-level violations that the commit introduces:

```sh
bb assay --format text --base HEAD
```

Assay can't compare with `HEAD` before the first commit, so each option
below skips the check until there is one.

### With a plain hook script

Save this as `.git/hooks/pre-commit` and make it executable:

```sh
#!/bin/sh
# Skip the check before the first commit.
git rev-parse --verify --quiet HEAD >/dev/null || exit 0
exec bb assay --format text --base HEAD
```

Git doesn't share `.git/hooks` through the repository. To share the hook,
keep it in a directory such as `.githooks` and run
`git config core.hooksPath .githooks` in each clone.

### With pre-commit or prek

With [pre-commit](https://pre-commit.com) or
[prek](https://github.com/j178/prek), add a local hook to
`.pre-commit-config.yaml`:

```yaml
repos:
  - repo: local
    hooks:
      - id: assay
        name: assay
        entry: bb assay --format text --base HEAD
        language: system
        pass_filenames: false
        files: ^(components|bases)/[^/]+/src/.*\.clj[cs]?$
```

The `files` pattern runs the hook only when a commit changes brick source.

### With devenv

With [devenv](https://devenv.sh), add the hook to `devenv.nix`:

```nix
git-hooks.hooks.assay = {
  enable = true;
  name = "assay";
  description = "Check Clojure code metrics with assay.";
  entry = "${pkgs.babashka}/bin/bb assay --format text --base HEAD";
  pass_filenames = false;
  files = "^(components|bases)/[^/]+/src/.*\\.clj[cs]?$";
};
```

## Check pull requests and pushes in CI

This GitHub Actions workflow runs assay on each pull request and each push
to `main`. Save it as `.github/workflows/assay.yml`:

```yaml
name: Assay

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  assay:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7.0.1
        with:
          # Assay compares with earlier commits, so it needs the history.
          fetch-depth: 0

      - uses: actions/setup-java@v6.0.1
        with:
          distribution: temurin
          java-version: "21"

      - uses: DeLaGuardo/setup-clojure@13.7.0
        with:
          bb: latest

      # On pull requests, compare with the target branch and fail only on
      # new error-level violations. On pushes to main, compare with the
      # commit before the push, but fail on any error-level violation.
      - name: Assay
        env:
          BASE_REF: ${{ github.base_ref }}
          BEFORE: ${{ github.event.before }}
        run: |
          args=(--format github --format html)
          if [ -n "$BASE_REF" ]; then
            args+=(--base "origin/$BASE_REF")
          else
            # BEFORE is all zeros for a new branch, and a force push can
            # leave it pointing at a commit that is gone.
            if git cat-file -e "${BEFORE}^{commit}" 2>/dev/null; then
              args+=(--base "$BEFORE")
            fi
            args+=(--fail-on all)
          fi
          bb assay "${args[@]}"

      - name: Upload assay report
        if: always()
        uses: actions/upload-artifact@v7.0.1
        with:
          name: assay-report
          path: target/assay/index.html
          if-no-files-found: ignore
```

Each run then has:

- Annotations on the code for each violation. Assay anchors violations of
  a whole brick to the brick's `deps.edn`.
- A job summary with the violations, the functions to review, the brick
  dependency graph, and the brick metrics.
- The HTML report, as the `assay-report` artifact.

Assay reports only error-level violations unless you pass `--warnings`.
Warnings point to code worth refactoring before it reaches an error, so
consider adding `--warnings` to the HTML report while leaving the hook
terse.
