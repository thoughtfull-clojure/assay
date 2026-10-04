# assay

Assay gathers code metrics about Clojure source, such as form counts and
cyclomatic complexity.

## Status

This project is early scaffolding. It counts forms. Planned metrics include
cyclomatic complexity and nesting depth.

## Usage

```sh
clojure -M:run src
```

The command prints a map of metrics for each Clojure file under the given
paths.

## Development

The development environment uses [devenv](https://devenv.sh). Run
`devenv shell` to enter it. The shell provides a JDK, the Clojure CLI,
and these tools:

- `cljfmt` formats Clojure code.
- `clj-kondo` lints Clojure code.
- `vale` checks Markdown prose against the Google developer style guide.
- `gitlint` checks commit messages.

Entering the shell installs Git hooks. On each commit, the hooks run
`cljfmt fix`, `clj-kondo`, and `vale` against staged files, and `gitlint`
against the commit message.

```sh
test        # run tests (kaocha)
lint        # lint src and test
fmt         # format src and test
devenv test # run tests and every Git hook against the whole repository
```

Tool configuration lives in `.config/`.

## License

> Copyright © technosophist
>
> This Source Code Form is subject to the terms of the Mozilla Public
> License, v. 2.0. If a copy of the MPL was not distributed with this
> file, You can obtain one at <https://mozilla.org/MPL/2.0/>.
