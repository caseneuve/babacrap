---
name: crap
description: Run and interpret babacrap CRAP analysis for babashka/Clojure projects. Use when asked to check CRAP score, run quality gates, inspect risky functions, or improve test/refactor priorities.
---

# CRAP Analysis Workflow

Use this skill when asked to run or interpret CRAP analysis.

CRAP combines cyclomatic complexity and test coverage:

```text
CRAP(m) = complexity(m)^2 * (1 - coverage(m))^3 + complexity(m)
```

Treat CRAP as a change-risk indicator, not a complete code-quality metric.

## Run dogfood CRAP in this repo

From the `babacrap` repo:

```sh
bb crap \
  --src src \
  --test test \
  --ns-regex 'babacrap.*' \
  --test-ns-regex 'babacrap.*-test' \
  --output target/dogfood-crap \
  --crap-threshold 30
```

For agent-readable output:

```sh
bb crap \
  --src src \
  --test test \
  --ns-regex 'babacrap.*' \
  --test-ns-regex 'babacrap.*-test' \
  --output target/dogfood-crap \
  --crap-threshold 30 \
  --format edn
```

## Run against another project

If babacrap is available on the target project's classpath:

```sh
bb -m babacrap.core \
  --src src \
  --test test \
  --ns-regex 'my.project.*' \
  --test-ns-regex '.*-test' \
  --output target/babacrap/coverage \
  --crap-threshold 30
```

For complexity-only triage without running Cloverage:

```sh
bb -m babacrap.core \
  --no-coverage \
  --src src \
  --crap-threshold 999
```

## Interpret results

Rules of thumb:

| CRAP score | Interpretation |
|---:|---|
| `1–10` | Usually fine |
| `10–20` | Worth a look |
| `20–30` | Risky; add tests or simplify |
| `>30` | CRAPpy; treat as a quality gate failure |
| `>50` | Strong warning |
| `>100` | Dangerous hotspot |

High complexity alone is not automatically bad. Low coverage alone is not
automatically bad. CRAP is highest when complex code is insufficiently covered.

## Remediation workflow

For each high-scoring function:

1. Read the function and nearby tests.
2. Identify whether risk comes mainly from complexity, missing coverage, or both.
3. If behavior is clear, add focused tests for uncovered branches first.
4. If behavior is hard to understand, refactor in small FCIS-style steps:
   - move effects to the imperative shell;
   - keep pure decisions in the functional core;
   - split independent concerns;
   - prefer data transformations over hidden state.
5. Re-run CRAP analysis.
6. For confidence after behavior changes, consider running the `mutate` skill.

Do not refactor just to game the score. A lower CRAP score is useful only when
it reflects clearer code, better tests, or both.

## Output guidance

Text output is for humans and includes a PASS/FAIL table.

EDN output is for agents and tooling. On successful runs, stdout contains only
the EDN payload. Cloverage chatter is captured and discarded. If Cloverage
fails, captured chatter is replayed to stderr.

## Related docs

For user-facing explanation, read `README.md`, especially:

- `CRAP score`
- `How to read CRAP scores`
- `Use from another babashka project`
