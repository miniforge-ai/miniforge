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

- `clj-kondo --lint components/compliance-scanner/src/ai/miniforge/compliance_scanner/exceptions_as_data.clj
  components/compliance-scanner/src/ai/miniforge/compliance_scanner/scan.clj
  components/compliance-scanner/test/ai/miniforge/compliance_scanner/exceptions_as_data/local_boundary_classification_te
  st.clj components/compliance-scanner/test/ai/miniforge/compliance_scanner/exceptions_as_data/output_format_test.clj
  components/compliance-scanner/test/ai/miniforge/compliance_scanner/scan_test.clj`
- `clojure -M:dev:test -e '(require (quote clojure.test) (quote
  ai.miniforge.compliance-scanner.exceptions-as-data.local-boundary-classification-test) (quote
  ai.miniforge.compliance-scanner.exceptions-as-data.output-format-test) (quote
  ai.miniforge.compliance-scanner.scan-test)) (let [r (clojure.test/run-tests (quote
  ai.miniforge.compliance-scanner.exceptions-as-data.local-boundary-classification-test) (quote
  ai.miniforge.compliance-scanner.exceptions-as-data.output-format-test) (quote
  ai.miniforge.compliance-scanner.scan-test))] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))'`
- `clojure -M:dev -e '(require (quote ai.miniforge.compliance-scanner.exceptions-as-data)) (let [result
  (ai.miniforge.compliance-scanner.exceptions-as-data/scan-repo "." nil)] (prn (:counts result)))'`

Scan delta on this branch:

```clojure
;; before
{:cleanup-needed 41, :fatal-only 129}

;; after
{:cleanup-needed 28, :fatal-only 126, :local-boundary 16}
```
