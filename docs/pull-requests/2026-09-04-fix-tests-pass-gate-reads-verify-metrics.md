<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(gate): :tests-pass reads verify's test metrics from the phase result

**Tier:** blocker / dogfood-enabler
**Theme:** gates, verify, implementer feedback

## Problem

`check-tests-pass` in `components/gate/src/ai/miniforge/gate/test.clj` read
`[:metadata :test-results]` off the artifact and returned a pass with a
`:no-tests` warning when that was nil. On the production verify path
(`enter-verify` in `phase_software_factory/verify.clj`) the phase result's
`[:result :output]` is nil and the test counts live in `[:result :metrics]`
(`:pass-count`, `:fail-count`, `:failures`, `:test-output`). The gate never
saw a result, so it logged `Gate :tests-pass passed` on every verify run,
including runs with failing tests. Checkpoint
`f413dd80-394c-46d0-86b8-46d3dbb33dd8`, every verify entry in
`gate-history.edn`, shows only `:policy-verify` denials; the failing tests
never registered as a gate failure.

A second gap sat downstream: `build-implement-task` read
`:phase/gate-failures` only from the implement phase result. Verify's gates
deny on the verify result and redirect to implement, so their structured
errors never reached the implementer's gate-denial section.

## Changes

### Gate (`components/gate/src/ai/miniforge/gate/test.clj`)

`apply-gate-validation` in `workflow/execution.clj` hands each gate the
entered ctx, whose `[:phase :result]` is the phase result. The gate now reads
that first:

1. A result whose `:metrics` carries `:fail-count` is test-bearing (the
   `phase/test-metrics` contract). Positive `:fail-count` denies with
   `{:type :tests-failed :message "N tests failed" :failures [...]}`.
2. A phase `:status :error` with zero counted failures (parse error, crashed
   or timed-out test command) denies with `{:type :verify-error}` carrying
   the phase's error message. Uncounted tests are not passed tests.
3. Otherwise the gate passes and reports `:pass-count`.
4. A phase result without `:fail-count` in its metrics (implement's
   tokens/cost metrics) is not a test result. The legacy artifact-metadata
   shape applies, and with neither source the `:no-tests` warning is
   unchanged.

New prose strings go through the gate messages catalog
(`config/gate/messages/en-US.edn`, keys `:tests-pass/*`).

### Implementer feedback

- `prior-gate-failures` in `phase_software_factory/implement.clj` gathers
  `:phase/gate-failures` from both the implement and verify phase results,
  implement first. Verify's `:tests-pass` and `:policy-verify` denials now
  render in the gate-denial section.
- `format-gate-failures-section` in `agent/implementer.clj` prints each
  `:failures` entry (test name and location) under its error line, so a
  `:tests-pass` denial names the tests to fix.

## Tests

- New `components/gate/test/ai/miniforge/gate/tests_pass_test.clj`, 7 tests:
  failing tests deny a nil artifact; phase error without failures denies;
  green verify allows with counts; phase result wins over artifact metadata;
  non-test-bearing phase result falls through; no source warns; the
  registered gate and its `:test` alias deny through `check-gates` with
  `:failures` intact.
- `implementer_test.clj`: a `:tests-pass` denial renders each failing test
  with its location.
- `implement_test.clj`: verify's gate failures reach `:task/gate-failures`
  after implement's own.

Run:

- `clojure -M:poly test brick:gate`
- `clojure -M:poly test brick:agent`
- `clojure -M:poly test brick:phase-software-factory`

## Notes

- The third commit used `MINIFORGE_STRATUM_BUDGET_MODE=warn`. `implement.clj`
  and `implementer.clj` are over the SL003 layer budget on main already (7 and
  8 layers, unchanged by this PR); the warn mode is the documented path for
  files still mid namespace-split.
- Behavior change beyond `:tests-pass`: verify's `:policy-verify` denials
  now also reach the implementer as gate failures. They travelled the same
  channel and were dropped by the same read.
