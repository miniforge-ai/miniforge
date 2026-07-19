<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# feat(phase): complete the phase-outcome authority — emit :skipped, route leave-handlers, fix resume

## Overview

Follow-up to #1243, which made `phase/outcome` the single derivation of a
phase's typed act but left two gaps noted in its PR doc. This closes both:

1. The five phase leave-handlers still built `:outcome` inline rather than
   reading the authority.
2. `:skipped` was schema-allowed and consumer-handled but never produced — a
   skipped phase reported `:success`, and resume filtered on `:success` only.

## Motivation

After #1243 the act derived in one place for the generic event path (Path A,
`runner-events`), but the per-phase leave-handlers (Path B,
`emit-phase-completed!`) each still computed `:outcome` as
`(if <verdict> :success :failure)`. That left the vocabulary split across six
sites and meant `:skipped` could never surface: release short-circuits with a
`phase/skipped` result, but its inline emit reported `:success`, and
`workflow-resume` treated only `:success` as completed.

## Changes in Detail

### `phase/outcome` recognizes skip (`components/phase`)

- New `skipped?` predicate reads the `:output` skip marker on the bare result
  and on the result nested under `:result`, so it answers on either emission
  path (leave-handler holds the bare result; the event builder receives it
  nested). `phase/outcome` gains a `:skipped` branch:
  refusal > failure > skipped > redirected > success. Kept internal to the
  component — only `phase/outcome` consumes it.

### Leave-handlers read the authority (`components/phase-software-factory`)

- verify, review, implement, plan, release now call
  `(phase/outcome result <verdict>)` instead of an inline
  `(if <verdict> :success :failure)`. Each still computes its own success
  verdict and passes it in.

### Resume respects `:skipped` (`components/workflow-resume`)

- `extract-completed-phases` filtered on `:success`; it now filters against a
  named `completed-outcomes` set (`#{:success :skipped}`), so a skipped phase is
  trimmed from the pipeline on resume instead of re-executed.
- The resume config's completed status set also includes `:skipped`, so the
  reconstructed context path trims skipped phases consistently with the
  extractor path.

## Behavior Change

- A skipped phase (today, only release when there is nothing to release) now
  reports `:phase/outcome :skipped` on its completion event instead of
  `:success`. Consumers already render `:skipped` (cli display, tui-views
  persistence). Resume counts it as completed. No other observable change.

## Scope Notes

- **`:blocked` and `:redirected` have no producers.** `phase/blocked` is called
  nowhere, and the redirect calls were removed in favor of FSM-verdict handling
  (see the comments at release.clj, review.clj, verify.clj). Routing the four
  non-skip leave-handlers is therefore behavior-neutral today; it unifies the
  vocabulary and means those acts would surface automatically if a phase ever
  produces them. The dead values are left in the schema and the resume set's
  exclusion (they would re-run) rather than removed — that is #1243's contract,
  not this PR's to retract.
- The `:status` FSM flag is unchanged, consistent with #1243.

## Testing Plan

- New: `phase-result` `outcome-skipped-test` and `skipped?-predicate-test`;
  a `skipped-result-reports-skipped` case in
  `phase-outcome-from-inner-result-test` (Path A emits `:skipped`); a `:skipped`
  case in `workflow-resume` `extract-completed-phases-test`; and a
  `reconstruct-context` integration case proving skipped phase-completion events
  are trimmed by the config-backed resume path.
- Regression: the five leave-handler namespaces compile; the smoke set (316
  tests) is green on every commit; `clj-kondo` clean (two pre-existing
  `verdicts` unused-private warnings in verify/release are non-fatal and out of
  scope).
- Verified: `bb pre-commit` green per commit; `bb poly:check` OK; each commit
  under the 200-line budget.

## Deployment Plan

Product is unreleased — no compatibility shims. The only behavior change is a
more accurate outcome on skipped phases, which existing consumers and resume
already handle. No data migration.

## PR Layering

Three commits, each independently valid and under the budget:

1. `feat(phase)` — `phase/outcome` recognizes skip + tests.
2. `refactor(phase-software-factory)` — route the five leave-handlers.
3. `fix(workflow-resume)` — `:skipped` counts as completed + test.

## Related

- Predecessor: #1243 (the phase-outcome authority); this completes its two
  noted follow-ups.

## Checklist

- [x] `skipped?` + `:skipped` branch in `phase/outcome` + tests
- [x] Five leave-handlers routed through `phase/outcome`
- [x] Resume treats `:skipped` as completed + test
- [x] `clj-kondo` clean, `poly check` OK, commit budget respected
- [x] Push and open PR
