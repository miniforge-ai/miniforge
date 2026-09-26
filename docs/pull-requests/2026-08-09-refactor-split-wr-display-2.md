<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split workflow_runner/display.clj — flip to extracted namespaces (rule 210, 2/2)

## Overview

Second and final PR of the rule-210 split of
`bases/cli/src/ai/miniforge/cli/workflow_runner/display.clj`. PR #1724 created
the ten sibling namespaces holding the moved code without touching `display.clj`.
This PR rewires `display.clj` to the nine namespaces its public surface uses.
It deletes the moved definitions and re-exports the eighteen vars used by
callers and tests.

`display.clj` goes from **8 real strata to 1**, clearing its SL003 violation.

## Motivation

`display.clj` was the worst remaining SL003 offender in `workflow_runner/`: 502
lines, 8 strata against a budget of 3. SL003 checks staged files, so any commit
touching `display.clj` must leave it within budget. Extraction and rewiring
therefore needed separate PRs.

## Changes in Detail

`display.clj` is now a single Layer 0 of re-export `def`s, grouped by owning
namespace:

| Re-exported vars | Owning namespace |
|---|---|
| `ansi-codes`, `colorize` | `display-ansi` |
| `format-duration` | `display-format` |
| `format-event-line` | `display-event-line` |
| `format-demo-line` | `display-demo-line` |
| `start-progress!` | `display-progress` |
| `extract-failed-tasks`, `extract-pr-urls`, `extract-phase-summaries` | `display-result-facts` |
| `format-compact-summary` | `display-summary` |
| `print-workflow-header`, `print-workflow-summary`, `print-pretty-result`, `print-result` | `display-print` |
| `print-error-header`, `print-namespace-resolution-help`, `print-babashka-fallback-help`, `print-general-debugging-help` | `display-error-help` |

Those eighteen are exactly the vars referenced through `display` in `bases/cli`.
Ten source namespaces use them: `workflow-runner`, `chain`, `context`,
`dashboard`, `execution`, `lifecycle`, `listing`, `provenance`, `sandbox`, and `setup`.
Tests also use them: `display_test.clj`, `display_output_test.clj`, and
`runner_control_wiring_test.clj`. Nothing else moves; no call site changes.

`display-summary-lines` is not required here — none of its vars are part of the
public surface; it reaches callers through `display-summary`.

### with-redefs

`(def x other/x)` creates a **new** var rooted at the current value of `other/x`.
It does not alias the original var. For `colorize`, `identical?` over the two
var objects returns `false`. What matters for the tests is which var a
caller resolves: `workflow_runner.clj`, `setup.clj` and `chain.clj` all call
`display/start-progress!`, so `with-redefs [display/start-progress! …]` in
`runner_control_wiring_test.clj` still intercepts them. The converse does not
hold — `with-redefs` on `display-progress/start-progress!` would not reach a
caller going through this namespace. Verified at the REPL, and the wiring test
passes unchanged.

No test redef target needed changing: the other redefs in `display_test.clj`
target `messages/t` and `app-config/*`, which the moved code calls directly in
its new home.

Docstrings are not duplicated onto the shim — they stay with the implementations,
so there is no second copy to drift.

## Testing Plan

- stratum-lint (pin `bef8657`) on `display.clj`: plain clean, and `--fix` on a
  scratch copy proposes **no changes**, confirming **1 real stratum**. (Its only
  suggestion during drafting was blank-line spacing between defs.
  The committed file adopts that spacing, so the dry run is now a no-op.)
- clj-kondo: 0 errors, 0 warnings.
- `display-test` + `display-output-test` + `runner-control-wiring-test`: 79 tests,
  185 assertions, 0 failures, 0 errors.
- Full `bb pre-commit`.

## Deployment Plan

No behaviour change. Callers previously invoked the implementations defined in
`display.clj`; this PR re-roots each var at the extracted namespace's copy of
that implementation. The copies moved verbatim in #1724.
Comparison against the originals over 152 paired inputs found no mismatches.
This proves behavioural equivalence, not object identity. Ships with the ordinary merge to
`main`.

## Related Issues/PRs

- Depends on: #1724 (extraction, merged).
- Rules: `standards/miniforge/languages/clojure` (210),
  `standards/miniforge/foundations/stratified-design` (001).

## Checklist

- [x] `display.clj` at 1 stratum, SL003 clear
- [x] All 18 externally-referenced vars re-exported
- [x] `with-redefs` interception preserved
- [x] stratum-lint plain + `--fix` dry run clean
- [x] clj-kondo clean
- [x] display / display-output / wiring tests green
