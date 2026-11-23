<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(verify): return the test verdict as verify's output so its gates can pass

## Overview

Verify now returns its test verdict as its phase `:output`. Before this change,
the gate envelope denied every verify on the default pipeline, including runs
whose tests passed.

## Motivation

`apply-gate-validation` hands gates the phase's canonical `:output`
(`[:result :output]`). Since the Ariadne 1d envelopes (#1558),
`gates->envelope` adds `:reason/missing-artifact` when that output is nil, and
any reason denies. Verify built its result with `phase/success` or
`phase/error` and no `:output`. The default verify gates are
`[:pre-verify-lint :tests-pass :coverage :policy-verify]`, and
`canonical-sdlc-v2.0.0` keeps them, so `leave-verify` failed every verify. No
run of the default pipeline could reach review or release.

## Changes in Detail

1. `verify.clj` `gate-output`: verify's canonical output,
   `{:verify/tests-passed? bool}`. Verify writes no code; the code under
   verification stays in the worktree and in the implement phase's artifact.
2. `verify.clj` `verify-result`: builds the success or error phase result and
   attaches that output. `enter-verify` calls it.
3. `gate/test.clj` `check-tests-pass` docstring: no longer says verify's
   output is nil. The gate still reads test counts from the phase result
   (`check-tests-pass`, #1888), because the verdict carries no counts.

The other gates are unaffected by the new output. `:policy-verify` judges the
implement phase's artifact when the gated artifact carries no code
(`policy-artifact`), as it did when the output was nil. `:coverage` reads
coverage metadata the verdict does not carry, so it passes with its no-data
warning as before. `leave-verify` still adds `:phase/verdict` into the same
map.

## Testing Plan

`verify_gate_validation_test.clj` runs the real `enter-verify` with the test
runner stubbed to return `verify.result/summary` values, the runner's own
result shape. It then runs verify's configured gates and envelope through the
public `gate` and `response` interfaces, the two calls the workflow makes:

1. passing tests: the output is the verdict, no missing-artifact reason, and
   the decision is allowed;
2. failing tests: denied, and not for a missing artifact.

The test ctx wires a judge that finds nothing, so `:policy-verify`'s acting
semantic rules do not fail closed for missing wiring.

Against main's `verify.clj` the new test fails 5 of its 6 assertions. With
the fix, the new test, `verify_test`, `verify_failure_modes_test`,
`verify_registration_test` and `gate/tests_pass_test` pass (45 tests, 143
assertions).

## Deployment Plan

No migration. The change takes effect on the next run.

## Related Issues/PRs

1. #1558 introduced the envelope's missing-artifact reason.
2. #1888 made `:tests-pass` read the phase result's test metrics.
3. Not in this PR: `:pre-verify-lint` still lints nothing in verify. It picks
   linters from `:code/files`, which implement's curated artifact replaces
   with `:code/file-paths`. Feeding it the paths makes `clj-kondo --lint src`
   run over the whole `src` tree and fail verify on findings the change did
   not introduce. It needs changed-files scoping first.

## Checklist

- [x] Regression test fails on main and passes here
- [x] Verify and gate tests pass locally
- [x] PR doc added (rule 721)
