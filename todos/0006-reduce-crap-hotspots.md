---
title: Reduce CRAP hotspots above 5
status: open
priority: medium
type: refactor
labels: []
created: 2026-06-15
parent: null
blocked-by: []
blocks: []
---

## Context

Dogfood CRAP analysis currently passes the default project gate (`--crap-threshold 30`), but a stricter "clean code" bar of CRAP `<= 5` highlights several existing hotspots. Reducing these should be treated as refactoring: preserve behavior, improve structure and/or targeted coverage, and avoid broad rewrites.

Current dogfood command:

```sh
bb crap --src src --test test --ns-regex 'babacrap.*' --test-ns-regex 'babacrap.*-test' --output target/dogfood-crap --crap-threshold 5
```

Known hotspots from the latest dogfood run include:

- `babacrap.detangle/get-access?` — `22.09`
- `babacrap.complexity/list-complexity` — `10.00`
- `babacrap.detangle/equality-dispatch-test` — `6.68`
- `babacrap.mutation/run-result` — `6.02`
- `babacrap.mutation/mutation` — `6.00`
- `babacrap.mutation/collect-not-mutants` — `5.30`
- `babacrap.mutation/fragment` — `5.30`
- `babacrap.cli/run-subcommand` — `5.01` after recent CLI changes may fluctuate around the cutoff depending on coverage

## Acceptance Criteria

- [ ] Dogfood CRAP with `--crap-threshold 5` passes or all remaining over-5 functions are explicitly accepted with rationale in this todo.
- [ ] Behavior is preserved; add characterization tests before refactoring any hotspot whose behavior is not already covered.
- [ ] Prefer small, focused refactors and/or targeted tests over broad rewrites.
- [ ] Keep clj-kondo hook and CLI complexity behavior aligned when changing complexity code.
- [ ] `bb test` passes.
- [ ] `bb lint` passes.
- [ ] `bb dry` passes.
- [ ] Run dogfood CRAP after changes and record the new highest scores in this todo.

## Affected Files

- `src/babacrap/detangle.clj` — likely split or simplify hotspot detector helpers; add tests where behavior is under-covered.
- `src/babacrap/complexity.clj` — carefully refactor `list-complexity` if needed; preserve hook parity.
- `src/babacrap/mutation.clj` — simplify mutation helper/control-flow hotspots or improve targeted coverage.
- `src/babacrap/cli.clj` — only if recent CLI helper remains above the stricter threshold.
- `test/babacrap/...` — characterization and regression tests for refactored behavior.

## Notes

- This is a refactor task, not a license to change public CLI behavior or complexity semantics.
- Use TDD/checkpoint commits: RED characterization, GREEN refactor, then cleanup.
- If this grows beyond a 1–2 hour pass, split into child todos by namespace/hotspot.
