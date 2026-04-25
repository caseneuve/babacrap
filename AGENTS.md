# AGENTS.md

Guidance for agents working on `babacrap`.

## Project purpose

`babacrap` is a babashka/Clojure CRAP analysis tool:

- compute cyclomatic complexity for `defn`-like forms;
- run Cloverage under babashka;
- join per-function complexity with coverage;
- report CRAP scores;
- run a simple mutation analysis pass;
- also export a clj-kondo hook for complexity-only linting.

Future mutation work should stay as a separate layer that reuses the same function inventory and configurable test command.

## Important external docs / source references

This repo was developed against local checkouts under `../external` from the project root.
Consult these before changing related behavior:

### clj-kondo

- `../external/clj-kondo/doc/hooks.md` — custom hook API and examples.
- `../external/clj-kondo/doc/config.md` — `:config-paths`, exported config, linter levels.
- `../external/clj-kondo/analysis/README.md` — analysis output shape, if adding analysis-driven features.
- `../external/clj-kondo/src/clj_kondo/hooks_api.clj` — functions available in hook code.
- `../external/clj-kondo/src/clj_kondo/impl/hooks.clj` — hook loading / SCI behavior.
- `../external/clj-kondo/src/clj_kondo/impl/analyzer.clj` — where analyze-call hooks are invoked.

### Cloverage

- `../external/cloverage/README.md` — user-facing options.
- `../external/cloverage/CHANGELOG.md` — current babashka compatibility note.
- `../external/cloverage/cloverage/bb.edn` — how Cloverage tests itself with babashka.
- `../external/cloverage/cloverage/src/cloverage/coverage.clj` — main entry point and runner flow.
- `../external/cloverage/cloverage/src/cloverage/report.clj` — raw stats shape / aggregation helpers.
- `../external/cloverage/cloverage/src/cloverage/report/raw.clj` — `raw-stats.clj` writer.

Note: Cloverage babashka support is in the git checkout tested during this work. Do not assume old released Clojars versions have equivalent behavior.

### babashka libraries

- `../external/babashka.process/README.md` and `../external/babashka.process/API.md` — process spawning, `process`, `shell`, `destroy-tree`, timeouts/shutdown handling.
- `../external/babashka.cli/README.md` and `../external/babashka.cli/API.md` — preferred direction for future CLI polish/subcommands.
- `../external/babashka.fs/README.md` and `../external/babashka.fs/API.md` — file operations such as `copy-tree`, `delete-tree`, `with-temp-dir`, `glob`, `which`.

### rewrite-clj

`src/babacrap/complexity.clj` and `src/babacrap/mutation.clj` use `rewrite-clj` nodes for source locations and static traversal. If changing parsing or node traversal, verify behavior against whitespace, comments, reader-discard forms, multi-arity `defn`, empty arity bodies, and generated nodes with incomplete metadata.

## Project layout

- `src/babacrap/complexity.clj` — static source parser and cyclomatic complexity inventory.
- `src/babacrap/coverage.clj` — Cloverage invocation and raw stats parsing/join helpers.
- `src/babacrap/core.clj` — CRAP CLI, CRAP formula, output, threshold handling.
- `src/babacrap/mutation.clj` — mutation discovery, in-place mutant application/restoration, test command execution.
- `resources/clj-kondo.exports/pk/babacrap/` — clj-kondo config export and hook namespace.
- `test_runner.clj` — lightweight integration test runner.
- `test/corpus/` — clj-kondo hook test corpus.
- `test/fixtures/` — small namespaced project for end-to-end CRAP tests.

## Commands

Run before handing off changes:

```sh
bb test
bb lint
```

Try the CLI on the fixture project:

```sh
bb crap --src test/fixtures/src --test test/fixtures/test --ns-regex 'demo.*' --test-ns-regex '.*-test' --crap-threshold 999
```

Complexity-only mode:

```sh
bb -m babacrap.core --no-coverage --src test/fixtures/src --format edn --crap-threshold 999
```

Mutation fixture run:

```sh
bb mutate --src test/fixtures/src/demo/core.clj --test-command 'bb -cp test/fixtures/src:test/fixtures/test -e "(require '\''[clojure.test :as t] '\''demo.core-test) (let [r (t/run-tests '\''demo.core-test)] (System/exit (+ (:fail r) (:error r))))"'
```

Dogfood checks used during development:

```sh
bb crap --src src --test test --ns-regex 'babacrap.*' --test-ns-regex 'babacrap.*-test' --output target/dogfood-crap --crap-threshold 30
bb mutate --src src --test-command 'bb -cp src:resources:test:test/fixtures/src:test/fixtures/test -e "(require '\''[clojure.test :as t] '\''babacrap.integration-test) (let [r (t/run-tests '\''babacrap.integration-test)] (System/exit (+ (:fail r) (:error r))))"' --timeout-ms 20000
```

## Development notes

- Keep the clj-kondo hook and CLI complexity behavior aligned when changing complexity rules; `babacrap.integration-test/hook-cli-parity-test` should catch drift on the fixture corpus.
- The clj-kondo hook runs in SCI and cannot depend on arbitrary project source. Keep hook code self-contained under `resources/clj-kondo.exports/pk/babacrap`.
- The CLI currently targets normal namespaced `.clj` / `.cljc` / `.bb` files under source paths. Bare scripts without an `ns` form are not first-class yet.
- Cloverage `raw-stats.clj` is Clojure data printed with `clojure.pprint`, not strict EDN. It can include reader literals such as regexes, so `babacrap.coverage/read-raw-stats` intentionally uses the Clojure reader with `*read-eval* false`.
- Generated output belongs under `target/` and should not be committed.
- Mutation currently mutates files in place one mutant at a time, writes sibling `*.babacrap.bak` backups, then restores them in `finally`. Startup also restores leftover backups for the selected source paths. Always verify `git status` after mutation runs. A future improvement could run mutants in a copied temp project using `babashka.fs/copy-tree` with excludes.

## Commit message style

Use the requested format:

```text
[what] one-line-descr.
```

Example:

```text
[babacrap] add initial CRAP analysis tool.
```
