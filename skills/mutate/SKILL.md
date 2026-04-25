---
name: mutate
description: Safely run babacrap mutation analysis, including target cleanliness checks, backup restoration, execution, and post-run verification. Use when asked to run bb mutate, mutation analysis, or mutant testing.
---

# `mutate` — Safe Mutation Analysis

Run `bb mutate` safely against a repo that may have unrelated uncommitted work.

## Why this skill exists

`babacrap.mutation` mutates source files **in place**, one mutant at a time, and restores them in a `finally` block. It also writes `*.babacrap.bak` sibling files as an extra safety net. The risk: if the process is SIGKILLed (OOM, `kill -9`, power loss) between the two writes, the mutated file is left on disk. A `finally` does not run in those cases.

The tool already refuses to run if any **mutation target file** is dirty (unless `--force`). Files you're editing that are *not* targets are fine to leave dirty.

## Workflow

### 1. Understand the scope

Before running, know which files will be mutated. Targets are whatever `--src` resolves to through `babacrap.complexity/source-files`:

```sh
bb -e "(require '[babacrap.complexity :as c]) (doseq [f (c/source-files [\"src\"])] (println f))"
```

Or just read the `--src` args — one file per path, or every `.clj`/`.cljc`/`.bb` under a directory.

### 2. Check target cleanliness

```sh
git status --porcelain -- <target-files...>
```

- **Empty output** → safe to run. Other dirty files in the tree don't matter.
- **Non-empty** → you have three options:
  - commit or stash the target files (`git stash push -- <files>`);
  - pass `--force` if you accept the SIGKILL risk and have read the section below;
  - move the work into a `git worktree` and run there.

### 3. Run

```sh
bb mutate --src <paths> --test-command '<cmd>' [--timeout-ms N] [--limit N] [--format edn]
```

Exit codes:

| Code | Meaning                                                      |
|------|--------------------------------------------------------------|
| `0`  | All mutants killed (or none generated)                       |
| `1`  | At least one mutant survived                                 |
| `2`  | CLI error (bad args)                                         |
| `3`  | Mutation targets have uncommitted changes (pass `--force`)   |

### 4. Verify afterwards

```sh
git status
find . -name '*.babacrap.bak' -print
```

If any `.babacrap.bak` remain, the runner did not finish cleanly. From a project that has babacrap on its classpath (i.e. where `bb mutate` works), restore from them:

```sh
bb -e "(require '[babacrap.mutation :as m]) (m/restore-backups! [\"src\"])"
```

Both `run` and `run-mutation-analysis` also restore leftover backups on startup, so the next `bb mutate` against the same paths will clean up automatically before it parses anything.

## When to use `--force`

Only when all of these hold:

- You understand that the mutation targets will be rewritten briefly during the run.
- You've read the files you're mutating and know nothing important in them is uncommitted.
- You accept that a SIGKILL could leave them in a mutated state (the `.babacrap.bak` gives you a one-shot recovery).

If any of those feel shaky, stash instead:

```sh
git stash push -- <target-files...>
bb mutate ...
git stash pop
```

## CRAP analysis (`bb crap`) is safe

`bb crap` is read-only. It parses source files and runs Cloverage, which only writes under `--output` (default `target/babacrap/coverage`). No guarding needed regardless of worktree state.

Caveat: Cloverage **loads** instrumented namespaces, so any top-level side effects in them will execute. This is a Cloverage property, not a babacrap one.

## Agent checklist

When a user asks you to run mutation analysis:

1. [ ] Resolve the target file list from `--src`.
2. [ ] Run `git status --porcelain -- <targets>`. If non-empty, surface the dirty files and ask how to proceed (stash / commit / `--force` / worktree). Don't pick for them.
3. [ ] Run `bb mutate ...`. Treat exit `3` as "I refused, user needs to decide" — print the tool's stderr and stop.
4. [ ] After the run, `git status` + scan for `.babacrap.bak`. If either surprises you, stop and report before taking action.
