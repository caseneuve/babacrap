# babacrap

Simple CRAP and mutation analysis for babashka/Clojure projects.

Babacrap currently has four pieces:

1. a clj-kondo config export for editor/CI cyclomatic-complexity warnings;
2. a babashka CLI that runs Cloverage, combines coverage with complexity, and
   prints CRAP scores;
3. a simple babashka-native mutation runner;
4. a prototype `detangle` command that emits deterministic investigation
   signals for code shapes that may braid independently varying concerns.

## CRAP score

Babacrap uses the usual CRAP formula:

```text
CRAP(m) = complexity(m)^2 * (1 - coverage(m))^3 + complexity(m)
```

`coverage` is a ratio from `0.0` to `1.0`. Complexity starts at `1` and adds
one for each decision point.

### How to read CRAP scores

CRAP is a change-risk indicator for one function. It is not a complete code
quality score. It does not judge names, architecture, duplication, performance,
or whether tests assert meaningful behavior. It asks a narrower question:

> Is this function complex enough, and uncovered enough, that changing it is
> risky?

A complex function can be fine when it is well tested. A poorly covered function
can be fine when it is trivial. CRAP gets high when complexity and missing
coverage appear together.

Rules of thumb:

| CRAP score | Interpretation |
|---:|---|
| `1–10` | Usually fine |
| `10–20` | Worth a look |
| `20–30` | Risky; add tests or simplify |
| `>30` | CRAPpy; treat as a quality gate failure |
| `>50` | Strong warning |
| `>100` | Dangerous hotspot |

Examples:

| Complexity | Coverage | CRAP | Meaning |
|---:|---:|---:|---|
| `1` | `0%` | `2` | Trivial and untested; usually low risk |
| `10` | `100%` | `10` | Complex but covered |
| `10` | `50%` | `22.5` | Risky; tests or refactoring needed |
| `10` | `0%` | `110` | High-risk hotspot |

When a score is high, improve it by adding tests for uncovered branches,
reducing cyclomatic complexity, or both. Babacrap's default examples use a
threshold of `30`, which is a common practical gate for CRAP analysis.

## What complexity counts

- `if`, `if-not`, `if-let`, `if-some`
- `when`, `when-not`, `when-let`, `when-some`
- `cond`, `condp`, `case`
- `cond->`, `cond->>`
- short-circuit `and` / `or`
- `try` / `catch`
- `for`, `doseq`, plus `:when` and `:while` filters

Quoted code is ignored. Nested `fn`, `fn*`, `defn`, `defn-`, and `defmacro`
bodies are not charged to the outer function.

This is intentionally a practical lint metric, not a full control-flow graph.

## Use from another babashka project

Add babacrap as a GitHub dependency in the target project's `bb.edn`:

```clojure
{:paths ["src" "test"]
 :deps {io.github.caseneuve/babacrap
        {:git/tag "2026-04-25-alpha.1"
         ;; Replace <sha> with the commit SHA for the tag above.
         :git/sha "<sha>"}}
 :tasks
 {crap
  {:doc "Run CRAP analysis"
   :task
   (apply shell
          "bb" "-m" "babacrap.core"
          "--src" "src"
          "--test" "test"
          "--ns-regex" "my.project.*"
          "--test-ns-regex" ".*-test"
          "--output" "target/babacrap/coverage"
          *command-line-args*)}

  mutate
  {:doc "Run mutation analysis"
   :task
   (apply shell
          "bb" "-m" "babacrap.mutation"
          "--src" "src"
          "--test-command" "bb test"
          *command-line-args*)}

  detangle
  {:doc "Run decomplecting investigation signals"
   :task
   (apply shell
          "bb" "-m" "babacrap.detangle"
          "--src" "src"
          *command-line-args*)}}}
```

Replace `my.project.*` with the namespaces you want to measure.

For local babacrap development, use a local dependency instead:

```clojure
{:deps {babacrap/babacrap {:local/root "../babacrap"}}}
```

The task examples above provide safe project defaults, so from the target
project you can run:

```sh
bb crap
bb mutate
bb detangle
```

When invoking babacrap directly, bare `bb crap` and bare `bb mutate` print help
and exit without analyzing or mutating files. Pass explicit options, or define
project tasks like the examples above.

Example output:

```text
CRAP analysis: FAIL — 1/3 over threshold 10.00
  CRAP | COMPLEX |    COVERAGE | LOCATION
-------+---------+-------------+---------------------------------------------
 16.31 |       7 | 42.9% (12/28) | src/my/project/foo.clj:10 my.project.foo/complicated
  5.20 |       3 | 66.7% (4/6)   | src/my/project/foo.clj:22 my.project.foo/ok
  2.03 |       2 | 80.0% (4/5)   | src/my/project/foo.clj:3  my.project.foo/simple
```

The header is `PASS` / `FAIL` relative to `--crap-threshold` and reports
`failures/total`. Use `--format edn` to get a machine-readable `{:results :failures :threshold}` map on stdout. On a successful run stdout contains only the EDN payload — Cloverage chatter is captured and discarded. If Cloverage fails, the captured chatter is replayed to stderr so the underlying error is visible.

Useful options:

```sh
bb crap --crap-threshold 30
bb crap --format edn
bb crap --no-coverage          # complexity-only, assumes 0% coverage
```

The command writes Cloverage raw data under the configured `--output` directory
and uses `raw-stats.clj` to compute per-function coverage.

### Assumptions / limitations

The current CLI expects normal namespaced source and tests on the babashka
classpath, usually:

```text
src/my/project/foo.clj
test/my/project/foo_test.clj
```

It is not yet aimed at bare `.bb` scripts without `ns` forms or code embedded
only in `bb.edn` tasks.

## Run mutation analysis

Bare `bb mutate` prints help and exits without collecting or applying mutants.
Provide explicit options or define a project task with safe defaults.

Useful options:

```sh
bb mutate --src src --test-command 'bb test'
bb mutate --src src --test-command 'bb test' --limit 20
bb mutate --src src --test-command 'bb test' --timeout-ms 20000
bb mutate --src src --test-command 'bb test' --format edn
```

The mutation runner currently mutates files in place one mutant at a time,
writes sibling `*.babacrap.bak` backups, runs the configured test command, and
restores the original source in a `finally` block. At startup it also restores
leftover backups for the selected source paths. Use git to verify the worktree
afterwards, especially if you interrupt the process.

Current mutators include:

- boolean flips: `true` ↔ `false`;
- comparison/operator swaps: `=` ↔ `not=`, `<` ↔ `<=`, `>` ↔ `>=`;
- arithmetic swaps: `+` ↔ `-`, `*` ↔ `/`, `inc` ↔ `dec`;
- logical/control swaps: `and` ↔ `or`, `if` ↔ `if-not`, `when` ↔ `when-not`;
- condition forcing for `if`/`when` forms;
- `(not x)` removal.

## Run detangle investigation

`detangle` is a prototype signal generator inspired by Rich Hickey's
"decomplecting" vocabulary. It does not apply refactorings and does not claim
to be a complete design review. It parses local source files and emits
evidence-backed questions about code shapes that often mix independently
varying concerns.

Useful options:

```sh
bb detangle --src src
bb detangle --src src --format edn
```

Current rules look for:

- hidden time/randomness calls such as `(System/currentTimeMillis)`;
- data dispatch through `case` or repeated `cond` equality checks over keys
  such as `:type`, `:kind`, or `:op`;
- repeated `instance?` checks over the same subject;
- repeated deep `get-in` paths in rule-like functions;
- local atom accumulation that is mutated and then dereferenced.

Text output is intended for humans. EDN output is intended for agents, editor
integrations, or follow-up tooling.

Agents can use bundled project skills for repeatable workflows:

- `skills/crap/` — run and interpret CRAP analysis.
- `skills/mutate/` — run mutation analysis safely.
- `skills/hickey/` — guide Simple Made Easy / decomplecting reviews around `bb detangle`.

## Use only the clj-kondo complexity linter

After adding babacrap as a dependency, copy exported clj-kondo configs:

```sh
clj-kondo --lint "$(bb print-deps --format classpath)" --copy-configs --skip-lint
```

Then enable the copied config in `.clj-kondo/config.edn`:

```clojure
{:config-paths ["imports/io.github.caseneuve/babacrap"]
 :linters
 {:babacrap/cyclomatic-complexity
  {:level :warning
   :max 8}}}
```

For local development without copying configs, point directly at the checkout:

```clojure
{:config-paths ["../babacrap/resources/clj-kondo.exports/io.github.caseneuve/babacrap"]}
```

Run clj-kondo normally:

```sh
clj-kondo --lint src test bb.edn
```

## Custom defn-like macros

If you have a macro with defn-like syntax, register it directly for the
clj-kondo hook:

```clojure
{:config-paths ["imports/io.github.caseneuve/babacrap"]
 :hooks
 {:analyze-call
  {my.app/defhandler io.github.caseneuve.babacrap/defn-like}}
 :linters
 {:babacrap/cyclomatic-complexity {:level :warning :max 8}}}
```

Note: `:lint-as` does not automatically run hooks for the lint-as target. If a
macro is linted as `clojure.core/defn`, still register the macro itself under
`:hooks :analyze-call`.

## Develop / test

```sh
bb test
bb lint
```

Try the CRAP CLI against the fixture project:

```sh
bb crap --src test/fixtures/src --test test/fixtures/test --ns-regex 'demo.*' --test-ns-regex '.*-test' --crap-threshold 999
```

Try mutation analysis against the fixture project:

```sh
bb mutate --src test/fixtures/src/demo/core.clj --test-command 'bb -cp test/fixtures/src:test/fixtures/test -e "(require '\''[clojure.test :as t] '\''demo.core-test) (let [r (t/run-tests '\''demo.core-test)] (System/exit (+ (:fail r) (:error r))))"'
```
