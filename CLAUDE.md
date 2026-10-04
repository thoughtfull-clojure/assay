# CLAUDE.md

This file guides Claude Code when it works in this repository.

## Project

`assay` gathers code metrics about Clojure source: form counts,
cyclomatic complexity, and similar measures. It parses source with
rewrite-clj so metrics see code as written: comments, reader macros,
and all.

All namespaces use the `systems.thoughtfull.*` prefix.

- `systems.thoughtfull.assay` is the public API (`analyze-file`,
  `analyze-string`).
- `systems.thoughtfull.assay.parse` parses source into rewrite-clj nodes.
- `systems.thoughtfull.assay.metrics` holds metric functions and the
  `metrics` registry. To add a metric, write a function from a `:forms`
  node to a number and register it.
- `systems.thoughtfull.assay.main` is the command line entry point.

## Commands

Run these inside `devenv shell`:

```sh
clojure -M:test                 # run tests (kaocha)
clojure -M:test --focus <var>   # run one test
lint                            # lint (clj-kondo)
fmt                             # format (cljfmt)
clojure -M:run src              # print metrics for files under src
devenv test                     # run tests and all Git hooks
```

## Git hooks

devenv installs the hooks when the shell starts (see `devenv.nix`):

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
`markdownlint.yaml`, and `lychee.toml`. Tools don't look there by
default, so always pass the path. The `lint` and `fmt` scripts and the Git
hooks already do. When you call a tool directly, use
`--config .config/cljfmt.edn`, `--config-dir .config/clj-kondo`, and so
on. Only files that must sit at the root stay there: `deps.edn`,
`tests.edn` (kaocha), `devenv.*`, and `.gitignore`.

Vale styles live in `.config/vale/` (the `StylesPath`). The directory
holds a copy of the Google style from errata-ai/Google v0.7.1. To update
it, copy a newer release over `.config/vale/Google/`. Add project jargon
that fails the spelling check to `.config/vale/config/vocabularies/Assay/accept.txt`.
