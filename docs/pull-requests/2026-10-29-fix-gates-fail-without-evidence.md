<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(gate): test, coverage and behavioral gates fail without evidence

## Overview

Three gates passed, with a warning, when they had nothing to check. Each
reported a pass for something it never checked. This PR makes each of them fail
without evidence, keeps the one zero-test pass a change-aware runner reports,
and fixes the Cargo summary parse that would otherwise read a Rust workspace as
zero tests.

## Motivation

1. `:tests-pass` passed with no test results anywhere, and passed a successful
   verify that counted 0 tests.
2. `:coverage` passed with no coverage data. Nothing in miniforge measures
   coverage, so it passed every verify.
3. `:behavioral` passed with no policy packs loaded, and passed when its policy
   check threw.
4. `parse-cargo` read only the first `test result:` line. `cargo test --workspace`
   prints one per test binary, and the first is often a library with no unit
   tests, so a workspace with passing integration tests counted 0 tests.

## Changes in Detail

1. `gate.test/check-tests-pass` fails with `:no-tests` (no results) or
   `:no-tests-ran` (a success that counted no tests). The one zero-test pass left
   is a change-aware runner that says there is nothing to test ("No changed
   bricks", "nothing to test"); the gate passes it with a `:nothing-to-test`
   warning.
2. Verify carries the runner's `:no-tests?` into
   `[:phase :result :metrics]`, where `:tests-pass` reads it.
3. `gate.test/check-coverage` fails with `:no-coverage`. The threshold default
   (80) is a named var, and an explicit nil threshold still falls back to it.
   `:coverage` leaves verify's default gates, which are now
   `[:pre-verify-lint :tests-pass :policy-verify]`.
4. `gate.behavioral/check-behavioral` fails with `:no-policy-packs` or
   `:behavioral-check-error`. A non-callable `:check-fn` still throws, as a
   programmer error.
5. `verify.output/parse-cargo` sums every binary's summary.
6. Gate catalog: new `:tests-pass/no-tests-ran` and
   `:tests-pass/nothing-to-test`; `:coverage/no-data` and the two
   `:behavioral` messages describe a failure instead of a skip.

## Behaviour change

A verify that ran no tests now fails `:tests-pass`, except when the runner
reports nothing to test for the change. A workflow that lists `:coverage`
fails until coverage is measured. A workflow that lists `:behavioral` fails
with no policy packs or when the check throws.

## Testing Plan

1. `tests_pass_test.clj`: no results fails; zero counted tests fails;
   runner-reported nothing-to-test passes with the warning; coverage without
   data fails; an explicit nil threshold uses the default; above and below the
   threshold are unchanged.
2. `behavioral_test.clj`: no packs (empty and absent key) fails; a throwing
   check fails, on both the injected and default check paths.
3. `verify_test.clj`: `:no-tests?` reaches `[:phase :result :metrics]`;
   default gates are `[:pre-verify-lint :tests-pass :policy-verify]`.
4. `verify_failure_modes_test.clj`: a three-binary Cargo workspace output
   counts 9 tests, and a failure in a later binary is counted.
5. After merging current `main` (which moved every gate message into the
   catalog, #2307, and added `gate.result`, #2382): the gate,
   phase-software-factory, phase and workflow component tests and the
   project integration tests that run verify pass.

## Deployment Plan

No migration. Takes effect with the next CLI build. Workflows that list
`:coverage` or `:behavioral` explicitly start failing those gates until they
have evidence.

## Related Issues/PRs

Follows #2246 (verify's canonical `:output`). Not in this PR:
`:policy-verify` / `:policy-review` still pass with no packs loaded. A normal
run always has the shipped standards pack, so this only affects a run that
passes `:policy-packs []`. The fix belongs in `gate/policy_pack.clj`, which is
over the SL003 layer budget on `main` and needs its mechanical split first.

## Checklist

- [x] Regression tests fail without the fix
- [x] Adversarial review and pinned-standards review
- [x] Merged with current `main`
- [ ] CI green, Copilot comments resolved
