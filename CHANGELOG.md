# Changelog

## 2026-04-25-alpha.1 - 2026-04-25

Initial alpha release.

- Add CRAP analysis CLI for namespaced babashka/Clojure projects.
- Compute cyclomatic complexity for `defn`, `defn-`, and `defmacro` forms.
- Run Cloverage under babashka and join form coverage to function ranges.
- Add clj-kondo config export for complexity-only linting.
- Add simple mutation analysis runner with dirty-target protection and backup restoration.
- Add project skill for safely running mutation analysis.
