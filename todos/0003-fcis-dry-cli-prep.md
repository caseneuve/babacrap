---
title: Prepare CLI code for bbin dispatcher with FCIS/DRY cleanup
status: open
priority: high
type: refactor
labels: [cli, fcis]
created: 2026-06-14
parent: null
blocked-by: []
blocks: [0001-bbin-installable-cli]
---

## Context

Before making `babacrap` installable with `bbin`, do a small behavior-preserving cleanup of duplicated CLI shell/report helpers and make functional-core / imperative-shell boundaries easier to see.

Initial scan found duplicated helpers across `core`, `mutation`, and `detangle`:

- `render-edn`
- `emit-result`
- `run` / `-main` shape
- `function-label` in `core` and `detangle`

Doing this before `0001-bbin-installable-cli.md` should reduce churn when adding the top-level dispatcher and help/exit-code tests.

## Acceptance Criteria

- [ ] Characterization tests or smoke assertions cover current help, validation-error, text output, EDN output, and exit-code behavior for `crap`, `mutate`, and `detangle` before refactoring shared CLI helpers.
- [ ] Shared CLI/report helpers that are truly identical are extracted to `babacrap.util` or another existing appropriate namespace.
- [ ] Only extract helpers with at least two real callers and identical semantics; do not introduce generic CLI frameworks, protocols, or new namespaces for this cleanup.
- [ ] `render-edn` and `emit-result` are extracted if their stdout/stderr behavior remains byte-for-byte compatible.
- [ ] `function-label` is extracted only if the shared helper matches both CRAP and detangle report semantics.
- [ ] Do not extract `run-result`, `run`, or `-main` unless the resulting code is simpler and tests prove exit/stdout/stderr behavior is unchanged.
- [ ] `src/babacrap/core.clj`, `src/babacrap/mutation.clj`, and `src/babacrap/detangle.clj` have clear FCIS section markers, in this order where applicable: `;; -- Pure --`, `;; -- Side effects --`, and `;; -- CLI entry point --`.
- [ ] Existing code is reviewed for FCIS compliance, not only rearranged: identify functions that mix transformation with I/O/process/env/filesystem concerns and either separate them when small/safe or document follow-up work when separation would be too broad for this todo.
- [ ] Pure helpers are positioned before side-effecting orchestration in each touched namespace, and side-effecting functions delegate to pure helpers rather than mixing I/O with transformation logic where practical.
- [ ] Existing functions are moved only enough to make FCIS boundaries clear; avoid broad rewrites.
- [ ] No user-visible CLI behavior changes are introduced.
- [ ] Existing `bb crap`, `bb mutate`, and `bb detangle` tasks retain current behavior.
- [ ] Mutation normal run collects mutants once and reuses that collection for dirty-target detection and execution, without changing `--limit`, `--force`, backup-restore, or dirty-worktree behavior; if this is not fixed here, create a follow-up todo documenting why and what behavior must be preserved.
- [ ] Tests cover any extracted helpers or changed orchestration paths where existing coverage is insufficient.
- [ ] `bb test` and `bb lint` pass.

## Affected Files

- `src/babacrap/util.clj` — shared helper home if extraction stays small.
- `src/babacrap/core.clj` — use shared helpers and add FCIS markers.
- `src/babacrap/mutation.clj` — use shared helpers, add FCIS markers, optionally avoid duplicate mutant collection.
- `src/babacrap/detangle.clj` — use shared helpers and add FCIS markers.
- `test/babacrap/...` — update/add focused tests only as needed.

## Notes

Keep this intentionally small. Do not split `detangle.clj` into multiple namespaces here; that is larger structural work and not required before `0001`.

Do not migrate `core` or `mutation` CLI parsing in this todo unless explicitly needed; this is a behavior-preserving cleanup before dispatcher work.

This todo should be completed before `0001-bbin-installable-cli.md`; `0001-bbin-installable-cli` is listed in `blocks` to document sequencing.
