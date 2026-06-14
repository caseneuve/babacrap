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
- `resources/clj-kondo.exports/io.github.caseneuve/babacrap/` — clj-kondo config export and hook namespace.
- `test_runner.clj` — lightweight integration test runner.
- `test/corpus/` — clj-kondo hook test corpus.
- `test/fixtures/` — small namespaced project for end-to-end CRAP tests.

## Development rules

## Stack / dependency policy

- Babashka-first Clojure project. Prefer libraries already available through `bb` or existing project deps.
- Do not add new dependencies, new namespaces, new protocols, or substantially new execution strategies without asking first.
- For CLI parsing, filesystem work, and process execution, prefer `babashka.cli`, `babashka.fs`, and `babashka.process` idioms over hand-rolled code.

## Test discipline

- Use TDD for behavior changes: write the failing test first (RED), implement the minimal fix (GREEN), then refactor.
- Run focused checks during edits, then run the full required checks before handing off.
- `bb test`, `bb lint`, and `bb dry` must pass before review / handoff.
- Move tests with code when extracting or relocating behavior.

## MUST

- **TDD.** RED → GREEN → refactor for non-trivial behavior changes.
- **FCIS.** Keep a functional core and imperative shell. Pure functions take all inputs as arguments and do not read env, slurp files, or shell out. Side-effecting functions orchestrate I/O and call pure helpers.
- **DRY.** Keep one source of truth per concern. Search existing namespaces before adding helpers.
- **Duplicate scan.** Run `bb dry` at the end of each development task and remove redundant duplicates it reports.
- **YAGNI.** Do not add abstractions before a second caller or clear need exists.
- **KISS.** Prefer the simplest solution satisfying the requirement.
- **Use babashka idioms.** Do not hand-roll behavior provided by `babashka.cli`, `babashka.fs`, or `babashka.process`.
- **Peer review before merge.** Larger changes should be reviewed by another agent via the comms channel before merge.
- **Ask before:** new dependencies, new namespaces, new protocols, new execution strategies, public CLI/schema changes, or broad rewrites.

## MUST NOT

- **Push without explicit permission.**
- **Write tests after implementation** for non-trivial behavior changes.
- **Hand-roll CLI parsing loops.** Use `babashka.cli/parse-opts` / `dispatch` when changing CLI parsing.
- **Use `java.io.File` / `(.exists …)` / `java.io.File/createTempFile` for new filesystem code.** Use `babashka.fs`.
- **Shell out to `which`.** Use `babashka.fs/which`.
- **Add verbose docstrings or narrative comments.** Prefer semantic naming; comments explain why, not what.
- **Introduce mutually-exclusive boolean flag groups.** Prefer a single enum-valued option.
- **Mix pure and impure code in one function.** Inject I/O results into pure helpers.

## Canonical dev flow

Anything bigger than a quick single-commit fix should follow this flow:

1. Create or identify a todo/work item when appropriate.
2. Branch with a short descriptive name; use a worktree for large stories.
3. Develop with TDD: RED → GREEN → refactor, using checkpoint commits as useful.
4. Run `bb test`, `bb lint`, and `bb dry`.
5. Request peer review via an inter-agent channel for larger changes.
6. Address findings in new commits; do not amend after review unless explicitly agreed.
7. Wait for human approval before push/merge when the user has not explicitly delegated it.

Direct-to-master is acceptable only for genuine quick fixes with no meaningful behavior change.

## Babashka idioms

Reference local external docs before inventing new patterns:

- `../external/babashka.cli/README.md` and `API.md` — use `parse-opts` with `:spec`, `:coerce`, `:validate`, `:require`, `:alias`; use `dispatch` for subcommands and `format-opts` for help.
- `../external/babashka.fs/README.md` and `API.md` — use `fs/path`, `fs/exists?`, `fs/which`, `fs/glob`, temp-dir/file helpers, `copy-tree`, `delete-tree`.
- `../external/babashka.process/README.md` and `API.md` — use `process`, `shell`, `destroy-tree`, `:continue true`, I/O redirection, and timeouts/shutdown handling as appropriate.

## FCIS namespace shape

Prefer this organization when adding or substantially changing namespaces:

```clojure
(ns babacrap.example
  (:require ...))

;; -- Pure --

(defn parse-input [x] ...)
(defn build-plan [opts] ...)

;; -- Side effects --

(defn run! [opts] ...)

;; -- CLI entry point --

(defn -main [& args] ...)
```

Unit tests should target pure helpers; integration tests should cover side-effecting flows and CLI behavior.

## Git rules

- Never `git push` without explicit permission.
- Never `git commit --amend` on a pushed branch.
- Prefer rebase over merge for local history tidying; only before review / sharing.
- Verify `git status` after mutation runs because mutation edits files in place before restoring them.

## Review protocol

Implementation of larger work is not complete until another agent has reviewed it.

1. Implementer announces a `review-request` with branch, commit range, todo/work item if any, summary, and test results.
2. Reviewer acks, runs relevant checks, reviews the diff, checks babashka idioms and FCIS boundaries, then replies with `APPROVED` or `CHANGES-REQUESTED` and concrete findings.
3. Implementer addresses findings in new commits and re-requests review if scope warrants.
4. Both sides use `OVER` until review is concluded; use `OUT` only after approval is acknowledged.

## Commands

Run before handing off changes:

```sh
bb test
bb lint
bb dry
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
- The clj-kondo hook runs in SCI and cannot depend on arbitrary project source. Keep hook code self-contained under `resources/clj-kondo.exports/io.github.caseneuve/babacrap`.
- The CLI currently targets normal namespaced `.clj` / `.cljc` / `.bb` files under source paths. Bare scripts without an `ns` form are not first-class yet.
- Cloverage `raw-stats.clj` is Clojure data printed with `clojure.pprint`, not strict EDN. It can include reader literals such as regexes, so `babacrap.coverage/read-raw-stats` intentionally uses the Clojure reader with `*read-eval* false`.
- Generated output belongs under `target/` and should not be committed.
- Mutation currently mutates files in place one mutant at a time, writes sibling `*.babacrap.bak` backups, then restores them in `finally`. Startup also restores leftover backups for the selected source paths. Always verify `git status` after mutation runs. A future improvement could run mutants in a copied temp project using `babashka.fs/copy-tree` with excludes.

## Commit message style

Use commit prefixes based on context:

- **Development/checkpoint commits on feature branches** should help reviewers follow the work. Use TDD flow markers when applicable:

  ```text
  [red] characterize current behavior
  [green] implement minimal fix
  [refactor] simplify after green
  ```

- **Larger todos with subtasks** should include the subtask ID and flow marker:

  ```text
  [1.1 red] characterize dispatcher help
  [1.1 green] add dispatcher help
  [1.2 refactor] extract shared option specs
  ```

- **Squash commits for todo/work-item branches** should reference the todo ID and category:

  ```text
  [feat(0001)] add bbin installable cli
  [refactor(0003)] prepare cli fcis dry cleanup
  [fix(0007)] restore mutation backups on startup
  ```

- **Direct commits on `master` without a todo/work item** should use only a category:

  ```text
  [docs] update dogfood guidance
  [chore] ignore classpath cache
  [test] cover mutation dirty targets
  ```

Use a concise imperative/lowercase summary and omit a trailing period.
