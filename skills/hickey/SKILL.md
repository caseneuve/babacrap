---
name: hickey
description: Review Clojure/Babashka code for complecting using Rich Hickey-inspired Simple Made Easy questions. Use when asked to detangle code, reduce complecting, apply FCIS, review architecture, or run babacrap detangle.
---

# Hickey Decomplecting Review

Use this skill when reviewing Clojure/Babashka code for unnecessary entanglement.

Core question:

> What is braided together here that could vary independently?

The goal is not to make code shorter or trendier. The goal is to make code
simpler in Rich Hickey's sense: less intertwined, less braided together, and
easier to reason about over time.

## First pass: run deterministic detangle signals

From the `babacrap` repo:

```sh
bb detangle --src src
```

For agent-readable output:

```sh
bb detangle --src src --format edn
```

For another project that has babacrap on the classpath:

```sh
bb -m babacrap.detangle --src src
bb -m babacrap.detangle --src src --format edn
```

If the target project has multiple source roots, pass `--src` more than once:

```sh
bb detangle --src src --src bb
```

## Interpret detangle findings

Treat findings as investigation prompts, not automatic refactoring orders.

Current rules look for:

- hidden time/randomness;
- data dispatch via `case` or repeated `cond` equality checks;
- repeated `instance?` checks;
- raw nested `get-in` shape coupled to business rules;
- local atom accumulation.

For each finding, ask:

1. What concerns are braided together?
2. Could the data shape be normalized earlier?
3. Could policy be separated from mechanism?
4. Could effects move to the shell?
5. Could branching become data, protocol, or multimethod dispatch?
6. Would the proposed change actually make the code simpler?

## Refactoring constraints

Prefer small refactors.

Do not refactor just to use protocols, multimethods, or abstractions.

Prefer:

- pure functions;
- explicit inputs;
- data transformations;
- maps/tables for static decisions;
- protocols only for stable type-based operations;
- multimethods only for open data-based dispatch;
- an imperative shell around a functional core.

## Suggested workflow

1. Run `bb detangle` to gather deterministic signals.
2. Read the relevant source around each finding.
3. Name the concerns that are currently braided together.
4. Propose the smallest refactor that separates those concerns.
5. Explain why the new shape is simpler.
6. Call out tradeoffs and risks.
7. Add characterization tests before changing behavior.

## Full reference

For detailed examples, decision trees, pattern catalog, and review checklists,
read:

```text
references/hickey-expanded.md
```

Only read the full reference when the task needs deeper architectural guidance.
