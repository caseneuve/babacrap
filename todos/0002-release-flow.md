---
title: Document date-based alpha release flow
status: open
priority: medium
type: chore
labels: [release, docs]
created: 2026-06-14
parent: null
blocked-by: [0001-bbin-installable-cli]
blocks: []
---

## Context

For stable `bbin install io.github.caseneuve/babacrap` usage, the project should have explicit release notes and a simple tag-based release flow. Use date versioning for simplicity. The first public installable release should be alpha.

Established direction:

- Add/maintain `CHANGELOG.md`.
- Use date-based versions with a `v` prefix. Alpha tags use `vYYYY-MM-DD-alpha.N`; stable tags use `vYYYY-MM-DD` or `vYYYY-MM-DD.N` for same-day stable patch releases.
- Document development, review, merge, release, and tag rules in `AGENTS.md`.
- Keep GitHub Actions as the objective gate for pushes to `master` and tags.

Desired workflow to document:

1. Branch from `master` to a dev branch matching the todo; never develop directly on `master`.
2. Implement with TDD discipline and checkpoint commits.
3. Done means all tests pass and the tool is manually dogfooded on its own code with `--crap-threshold 30`; the run must pass with zero functions over threshold.
4. Mutation testing is only run from a clean git worktree, because it mutates source files in place and must not risk polluting unrelated dirty changes if interrupted or broken.
5. Ask for review and wait for approval.
6. After approval, squash + merge to `master`, delete the dev branch, and mark the todo done.
7. For release prep, branch from current `master`; do not make release-prep commits directly on `master`.
8. Update changelog/release docs on the release-prep branch, run checks, review, then squash + merge to `master`.
9. Wait for GitHub Actions on `master` to pass.
10. Create and push an annotated release tag, wait for tag CI, then create the GitHub release.

## Acceptance Criteria

- [ ] `CHANGELOG.md` documents the first alpha release notes or an Unreleased section ready for the first alpha release.
- [ ] Version/tag format is documented exactly: alpha tags are `vYYYY-MM-DD-alpha.N`; stable tags are `vYYYY-MM-DD` or `vYYYY-MM-DD.N` for same-day stable patch releases.
- [ ] `AGENTS.md` includes development workflow instructions: todo branch naming, no direct master work, TDD/checkpoint commits, review gate, squash merge, dev branch deletion, and todo completion.
- [ ] `AGENTS.md` includes done criteria: `bb test`, `bb lint`, and manual dogfood CRAP analysis on this repo with `--crap-threshold 30` and zero functions over threshold.
- [ ] `AGENTS.md` includes the exact dogfood CRAP command to run: `bb crap --src src --test test --ns-regex 'babacrap.*' --test-ns-regex 'babacrap.*-test' --output target/dogfood-crap --crap-threshold 30`.
- [ ] `AGENTS.md` documents mutation-safety rule: never run mutation testing on a dirty worktree; verify `git status --short` is clean first because mutation mutates files in place.
- [ ] `AGENTS.md` includes release-flow instructions: release-prep branch from `master`, changelog update, required checks, review, squash merge to `master`, wait for GitHub Actions, annotated tag creation, GitHub release creation, and public bbin smoke test.
- [ ] GitHub Actions run on push to `master` and tags, and include tests, lint, dogfood CRAP analysis, and bbin install smoke tests for the installed `babacrap` command.
- [ ] CI bbin setup is concrete: CI either installs `bbin` explicitly or uses the checked-out `/home`-independent install script/command; smoke tests use `BABASHKA_BBIN_BIN_DIR=$(mktemp -d)` and execute via `$tmp/babacrap-dev` rather than relying on PATH mutation.
- [ ] Release docs mention that `bbin` defaults to latest tag when no `--git/tag`, `--git/sha`, or `--latest-sha` is provided.
- [ ] Release docs include the intended first alpha commands, including annotated tag and release creation, e.g. `git tag -a vYYYY-MM-DD-alpha.1 -m "vYYYY-MM-DD-alpha.1"`, `git push origin vYYYY-MM-DD-alpha.1`, and `gh release create ...`.
- [ ] `bb test` and `bb lint` pass after doc/CI changes.

## Affected Files

- `CHANGELOG.md` — release notes and version history.
- `AGENTS.md` — durable release process for future agents.
- `README.md` — link to changelog/release install guidance so release/install behavior is user-visible.

## Notes

This is blocked by `0001-bbin-installable-cli`, because the release notes, CI bbin smoke tests, and public install guidance should describe the installable CLI accurately.

Open design note: keep release creation semi-manual at first. GitHub Actions should validate pushes to `master` and tags. Avoid automatically tagging every green `master` commit; `bbin` installs latest tags by default, so accidental tags/releases would publish too eagerly.
