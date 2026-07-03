<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Exceptions-as-Data Local Boundary Classification

Branch: `fix/exceptions-local-boundary-classification`

## Summary

The exceptions-as-data scanner previously reported documented local compatibility
throwers as actionable cleanup rows, even when the production namespace already
had a canonical anomaly-returning function and retained the thrower only as a
boundary bridge for legacy callers.

This PR adds a distinct `:local-boundary` classification for those documented
component-local wrappers. The top-level compliance scanner still reports only
`:cleanup-needed` rows as standards debt; `:fatal-only` and `:local-boundary`
remain in the raw scanner output for audit visibility.

## Scope

- Carry enclosing `defn` / `defn-` docstring and metadata through the AST walk.
- Classify only documented local boundary wrappers:
  - deprecated `exceptions-as-data` compatibility throwers that point to a
    preferred data-returning equivalent
  - documented boundary wrappers around canonical anomaly-returning functions
  - documented `response/throw-anomaly!` bridges with named data equivalents
- Preserve ordinary component throwers as `:cleanup-needed`.
- Add scanner tests for local boundary, deprecated compatibility, top-level
  filtering, and output counts.

## Verification

- `clj-kondo` on the scanner source and touched tests
- focused `clojure -M:dev:test` coverage for local boundary classification,
  output shape, and top-level scan filtering
- raw exceptions-as-data scanner count check

Scan delta on this branch:

```clojure
;; before
{:cleanup-needed 41, :fatal-only 129}

;; after
{:cleanup-needed 28, :fatal-only 126, :local-boundary 16}
```
