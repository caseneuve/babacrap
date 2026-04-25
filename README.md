# babacrap

CRAP analysis for babashka/Clojure projects.

Babacrap currently has three pieces:

1. a clj-kondo config export for editor/CI cyclomatic-complexity warnings;
2. a babashka CLI that runs Cloverage, combines coverage with complexity, and
   prints CRAP scores;
3. a simple babashka-native mutation runner.

## CRAP score

Babacrap uses the usual CRAP formula:

```text
CRAP(m) = complexity(m)^2 * (1 - coverage(m))^3 + complexity(m)
```

`coverage` is a ratio from `0.0` to `1.0`. Complexity starts at `1` and adds
one for each decision point.

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

## Use the CRAP CLI from another babashka project

Add babacrap as a dependency in the target project's `bb.edn`:

```clojure
{:paths ["src" "test"]
 :deps {babacrap/babacrap {:local/root "../babacrap"}}
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
          *command-line-args*)}}}
```

Replace `my.project.*` with the namespaces you want to measure.

Run:

```sh
bb crap
```

Example output:

```text
CRAP analysis
-------------
src/my/project/foo.clj:10 my.project.foo/complicated
  complexity: 7
  coverage:   42.9% (12/28 forms)
  CRAP:       16.31
```

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

Add a task next to your `crap` task:

```clojure
{:tasks
 {mutate
  {:doc "Run mutation analysis"
   :task
   (apply shell
          "bb" "-m" "babacrap.mutation"
          "--src" "src"
          "--test-command" "bb test"
          *command-line-args*)}}}
```

Run:

```sh
bb mutate
```

Useful options:

```sh
bb mutate --limit 20
bb mutate --timeout-ms 20000
bb mutate --format edn
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

## Use only the clj-kondo complexity linter

From a target project, add this to `.clj-kondo/config.edn`:

```clojure
{:config-paths ["../babacrap/resources/clj-kondo.exports/pk/babacrap"]
 :linters
 {:babacrap/cyclomatic-complexity
  {:level :warning
   :max 8}}}
```

Run clj-kondo normally:

```sh
clj-kondo --lint src test bb.edn
```

## Custom defn-like macros

If you have a macro with defn-like syntax, register it directly for the
clj-kondo hook:

```clojure
{:config-paths ["../babacrap/resources/clj-kondo.exports/pk/babacrap"]
 :hooks
 {:analyze-call
  {my.app/defhandler pk.babacrap/defn-like}}
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
