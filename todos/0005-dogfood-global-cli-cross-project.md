---
title: Dogfood global babacrap CLI against another project
status: open
priority: medium
type: chore
labels: [dogfood, cli]
created: 2026-06-14
parent: null
blocked-by: [0001-bbin-installable-cli]
blocks: []
---

## Context

After `babacrap` has a global/bbin-installed executable, dogfood it from a project outside the `babacrap` checkout. This verifies the CLI works in the target project's working directory and that coverage mode can see the target project's source/test classpath.

The motivating case is any separate Babashka/Clojure project with its own `src` and `test` paths: running `babacrap` from this repository can work for complexity-only mode, but coverage mode may fail if Cloverage cannot resolve the target project's namespaces on the effective classpath.

## Acceptance Criteria

- [ ] Install or invoke the current `babacrap` CLI in the intended global/tool form from `0001-bbin-installable-cli`.
- [ ] From a separate sample project outside the `babacrap` checkout, run complexity-only analysis successfully with relative paths, e.g. `babacrap crap --no-coverage --src src`.
- [ ] From the same separate sample project, run detangle successfully with relative paths, e.g. `babacrap detangle --src src`.
- [ ] Attempt coverage mode from the same separate sample project using that project's source/test paths and namespace regexes.
- [ ] If coverage mode succeeds, capture the command and summarize the real coverage-adjusted CRAP output.
- [ ] If coverage mode still fails, capture the exact command/error and create a focused follow-up todo for classpath/test-command/Cloverage integration.
- [ ] Document any required invocation guidance for cross-project use in `README.md` or `AGENTS.md` if it is not obvious from the `0001` docs.

## Affected Files

- `README.md` — document cross-project/global invocation guidance if needed.
- `AGENTS.md` — record dogfood command(s) if they become useful routine checks.
- `todos/...` — add a focused follow-up if coverage mode exposes a separate classpath/test execution limitation.

## Notes

Keep this as dogfooding/validation, not a broad feature implementation. Do not add new coverage configuration options here unless they are already part of the installable CLI work; create a separate todo for any new public CLI behavior.
