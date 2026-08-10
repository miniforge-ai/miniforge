# refactor(cli): split workflow_runner/display.clj — flip to extracted namespaces (rule 210, 2/2)

## Overview

Second and final PR of the rule-210 split of
`bases/cli/src/ai/miniforge/cli/workflow_runner/display.clj`. PR #1724 created
the ten sibling namespaces holding the moved code without touching `display.clj`.
This PR flips `display.clj` over to them: it now requires the nine namespaces its
public surface draws from, the moved definitions are deleted, and the eighteen
vars its callers and tests use are re-exported.

`display.clj` goes from **8 real strata to 1**, clearing its SL003 violation.

## Motivation

`display.clj` was the worst remaining SL003 offender in `workflow_runner/`: 502
lines, 8 strata against a budget of 3. The split had to be two PRs because SL003
is a staged-file gate — a commit that stages `display.clj` must leave it inside
the budget in that same commit, so extraction and flip could not be interleaved.

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

Those eighteen are exactly the vars referenced through the `display` alias
anywhere in `bases/cli` — eleven source namespaces (`workflow-runner`, `chain`,
`context`, `dashboard`, `execution`, `lifecycle`, `listing`, `provenance`,
`sandbox`, `setup`) plus `display_test.clj`, `display_output_test.clj` and
`runner_control_wiring_test.clj`. Nothing else moves; no call site changes.

`display-summary-lines` is not required here — none of its vars are part of the
public surface; it reaches callers through `display-summary`.

### with-redefs

A re-export `def` binds the same var object, and every caller resolves through
the `display` alias, so `with-redefs [display/start-progress! …]` in
`runner_control_wiring_test.clj` still intercepts calls made from
`workflow_runner.clj`, `setup.clj` and `chain.clj`. No test redef target needed
changing: the other redefs in `display_test.clj` target `messages/t` and
`app-config/*`, which the moved code calls directly in its new home.

Docstrings are not duplicated onto the shim — they stay with the implementations,
so there is no second copy to drift.

## Testing Plan

- stratum-lint (pin `bef8657`) on `display.clj`: plain clean; `--fix` on a scratch
  copy proposes only blank-line spacing between defs, which is adopted here, and
  confirms **1 real stratum**.
- clj-kondo: 0 errors, 0 warnings.
- `display-test` + `display-output-test` + `runner-control-wiring-test`: 79 tests,
  185 assertions, 0 failures, 0 errors.
- Full `bb pre-commit`.

## Deployment Plan

No behaviour change: every re-exported var is the same var object the callers
were already invoking. Ships with the ordinary merge to `main`.

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
