---
title: Make babacrap installable with bbin
status: open
priority: high
type: feature
labels: [bbin, cli]
created: 2026-06-14
parent: null
blocked-by: []
blocks: []
---

## Context

`babacrap` is public at `io.github.caseneuve/babacrap` and should be installable as a user-facing command with `bbin`. The project has three commands today (`crap`, `mutate`, `detangle`), so the installed binary should be a top-level dispatcher rather than only exposing one namespace.

Established direction:

- Add a `babacrap` binary via `:bbin/bin` in `bb.edn`.
- The binary dispatches subcommands: `crap`, `mutate`, `detangle`.
- No `help` subcommand is required; `babacrap help` is intentionally treated as an alias for top-level help and exits successfully.
- Top-level no-arg, `-h`, and `--help` invocations print top-level help.
- Unknown subcommands print top-level help or a clear error plus top-level help, and exit non-zero.
- Every subcommand supports `-h` / `--help` with explanation and examples.
- Subcommand arguments are forwarded unchanged to the existing command implementation.
- Subcommand exit codes are preserved.
- Existing `bb crap`, `bb mutate`, and `bb detangle` tasks remain compatible.
- Check `/home/piotr/git/external/babashka.cli/` before implementing to follow idiomatic babashka CLI patterns.

## Acceptance Criteria

- [ ] `src/babacrap/cli.clj` or equivalent top-level entrypoint exists and dispatches `babacrap crap`, `babacrap mutate`, and `babacrap detangle` to the existing command implementations.
- [ ] The new top-level dispatcher uses idiomatic `babashka.cli` facilities where appropriate, after checking `/home/piotr/git/external/babashka.cli/`.
- [ ] The implementation does not hand-roll subcommand parsing beyond the minimal glue needed to preserve existing command behavior.
- [ ] Top-level `babacrap`, `babacrap -h`, and `babacrap --help` print help, include available subcommands, and exit successfully without analyzing or mutating files.
- [ ] Each subcommand supports `-h` / `--help`, exits successfully, includes command-specific explanation plus examples, and performs no analysis or mutation in help mode.
- [ ] Unknown subcommands produce a clear diagnostic, include top-level usage, and exit non-zero.
- [ ] `babacrap help` behavior is intentional and tested: it is accepted as a top-level help alias and exits successfully.
- [ ] Subcommand arguments are forwarded unchanged; tests include at least one command-specific option such as `babacrap detangle --src test/fixtures/src --format edn`.
- [ ] Subcommand exit codes are preserved, including validation/error exits from the underlying command.
- [ ] Existing development tasks still work: `bb crap`, `bb mutate`, and `bb detangle` retain their current behavior.
- [ ] `bb.edn` contains `:bbin/bin {babacrap {:main-opts ["-m" "babacrap.cli"]}}` or equivalent valid bbin config.
- [ ] README documents the primary install command: `bbin install io.github.caseneuve/babacrap`.
- [ ] README documents installed command shape: `babacrap crap ...`, `babacrap mutate ...`, and `babacrap detangle ...`.
- [ ] README documents local/dev install without overriding a user install, using `--as babacrap-dev`.
- [ ] README documents bleeding-edge install with `--latest-sha`.
- [ ] AGENTS.md documents the local bbin test rule: always install development copies as `babacrap-dev`, never as `babacrap`.
- [ ] Tests cover dispatcher help/no-work behavior, unknown-subcommand behavior, argument forwarding, exit-code preservation, and subcommand dispatch/help behavior.
- [ ] Local isolated bbin install succeeds and smoke tests pass using a PATH-safe temporary bin directory:
  - [ ] `tmp=$(mktemp -d)`
  - [ ] `BABASHKA_BBIN_BIN_DIR=$tmp /home/piotr/git/external/bbin/bbin install . --as babacrap-dev`
  - [ ] `$tmp/babacrap-dev --help`
  - [ ] `$tmp/babacrap-dev crap --help`
  - [ ] `$tmp/babacrap-dev mutate --help`
  - [ ] `$tmp/babacrap-dev detangle --help`
- [ ] `bb test` and `bb lint` pass.

## Affected Files

- `bb.edn` — add bbin binary metadata.
- `src/babacrap/cli.clj` — new top-level dispatcher.
- `src/babacrap/core.clj` — possibly improve CRAP help examples if needed.
- `src/babacrap/mutation.clj` — possibly improve mutation help examples if needed.
- `src/babacrap/detangle.clj` — possibly improve detangle help examples if needed.
- `test/babacrap/...` — add dispatcher/help tests.
- `README.md` — add bbin install and usage docs.
- `AGENTS.md` — add dev-install safety rule.

## E2E Spec

GIVEN a clean checkout of public `io.github.caseneuve/babacrap`
WHEN a user runs `bbin install io.github.caseneuve/babacrap`
THEN a `babacrap` executable is installed.

GIVEN that executable
WHEN the user runs `babacrap`, `babacrap -h`, or `babacrap --help`
THEN top-level help is printed, no analysis or mutation is performed, and the process exits 0.

GIVEN that executable
WHEN the user runs `babacrap crap --help`, `babacrap mutate --help`, or `babacrap detangle --help`
THEN command-specific help with examples is printed, no analysis or mutation is performed, and the process exits 0.

GIVEN that executable
WHEN the user runs a real subcommand such as `babacrap detangle --src test/fixtures/src --format edn`
THEN the underlying command receives the arguments unchanged and returns the expected output and exit code.

GIVEN that executable
WHEN the user runs an unknown subcommand
THEN a useful diagnostic plus usage is printed and the process exits non-zero.

## Notes

Use `/home/piotr/git/external/bbin/` packaging docs and `/home/piotr/git/external/babashka.cli/` CLI docs/source as references before implementation.
