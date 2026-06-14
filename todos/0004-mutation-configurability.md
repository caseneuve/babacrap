---
title: Add mutation CLI path exclusions for expected-noisy targets
status: open
priority: medium
type: feature
labels: [cli, mutation]
created: 2026-06-14
parent: null
blocked-by: []
blocks: []
---

## Context

The fixture mutation dogfood command intentionally reports surviving mutants and exits non-zero. That is useful as a demo of mutation reporting, but noisy as a routine dogfood/check command.

Add focused configurability so users can scope mutation runs away from known-noisy files without changing the default behavior. This should help project dogfood and downstream users who have fixtures, generated code, or intentionally uncovered files that should not participate in mutation gates.

Function, namespace, and richer selector exclusions are out of scope for this first pass unless a separate todo explicitly adds them.

## Acceptance Criteria

- [ ] Users can repeat `--exclude PATH-REGEX` to exclude mutants whose normalized reported source filename (`:filename`, with `/` separators) matches any regex.
- [ ] Defaults remain unchanged: running `bb mutate` without `--exclude` mutates the same targets as before.
- [ ] Exclusions are applied after startup backup restore and mutant collection, and before dirty-target detection, `--limit`, display ID assignment, reporting, and execution.
- [ ] Invalid exclude regexes are CLI validation errors: exit `2`, stderr identifies the bad pattern, and usage is shown.
- [ ] Excluded mutants are omitted from text and EDN results; v1 does not add `:excluded` counts or details.
- [ ] Display IDs are dense after exclusion and `--limit` selection.
- [ ] `--limit` applies after exclusions to the remaining mutants.
- [ ] Dirty-target detection considers only non-excluded targets; an excluded dirty file does not block the run, while a non-excluded dirty file still exits `3` unless `--force` is passed.
- [ ] `--force` bypasses dirty checks only for non-excluded targets, preserving current force behavior for the selected run.
- [ ] Startup backup restore still scans all `:src-paths`, including excluded files, because stale backup cleanup is safety behavior rather than execution targeting.
- [ ] If all mutants are excluded, no test command is executed, text output reports the existing zero-mutant PASS state, EDN output has empty `:results`, and exit is `0`.
- [ ] Help text documents `--exclude PATH-REGEX`, repeatability, path matching semantics, and includes at least one example.
- [ ] Tests cover default behavior, invalid regex, repeated exclusions using OR semantics, excluded dirty file vs non-excluded dirty file, `--limit` after exclusion, all-mutants-excluded behavior, and text/EDN consistency.
- [ ] Dogfood guidance is updated in `AGENTS.md` at minimum so fixture mutation noise is avoidable or clearly marked expected-failing; update `README.md` too if user-facing mutation commands are documented there.
- [ ] `bb test`, `bb lint`, and `bb dry` pass.

## Affected Files

- `src/babacrap/mutation.clj` — parse `--exclude`, validate regexes, normalize reported filenames for matching, filter collected mutants before dirty checks/execution, and assign dense IDs after filtering/limit.
- `test/babacrap/integration_test.clj` — focused CLI and orchestration coverage.
- `AGENTS.md` — update canonical dogfood guidance for mutation noise.
- `README.md` — update if user-facing mutation CLI examples/options are documented there.
- `bb.edn` — optional dogfood task/config update if the design includes one.

## E2E Spec

GIVEN a source file that produces surviving mutants
AND a mutation command configured with `--exclude PATH-REGEX` matching that file's normalized reported filename
WHEN the user runs `bb mutate` with the exclusion option
THEN no mutants from the excluded file are executed
AND dirty-target detection ignores the excluded file
AND display IDs are dense for remaining mutants
AND the command exit code is based only on non-excluded mutants.

GIVEN all collected mutants match one or more `--exclude PATH-REGEX` options
WHEN the user runs `bb mutate`
THEN the test command is not executed
AND text output reports the existing zero-mutant PASS message
AND EDN output contains empty `:results`
AND the command exits `0`.

## Notes

Do not implement this as part of FCIS/DRY CLI prep. Treat it as a separate public CLI change: use TDD, preserve existing defaults, and avoid broad CLI parser rewrites unless required.

Preferred final semantics:

> `--exclude PATH-REGEX` is repeatable and excludes mutants whose normalized reported source filename (`:filename`, with `/` separators) matches any regex. Exclusions are applied after startup backup restore and mutant collection, but before dirty-target detection, `--limit`, display ID assignment, reporting, and execution. Invalid regexes are CLI validation errors with exit `2`. Excluded mutants are omitted from text/EDN results.
