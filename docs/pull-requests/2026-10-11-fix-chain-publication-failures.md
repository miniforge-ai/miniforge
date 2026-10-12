<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: propagate chain publication failures

## Overview

Stop chain execution from reporting success after a lifecycle event is rejected or fails publication.
This addresses the caller-side failure path required by N2.CH.5 and N3 §9.1.

## Motivation

The sequential executor currently discards publication results before child execution and at terminal transitions.
That permits dependent execution and successful return values after missing lifecycle evidence.

## Changes in Detail

- Centralize publication receipt handling for chain and step events.
- Refuse dependent execution after a failed start or step-terminal publication.
- Preserve child identity while surfacing publication failure through the chain failure path.
- Never return a successful result after the terminal publication fails.
- Attempt the chain failure event when completion publication fails, preserving the original error if that fallback is unavailable.
- Use the same failure fallback when start publication fails, before returning without child execution.

## Testing Plan

Exercise failures at every lifecycle boundary, rejected or mismatched receipts, and normal successful execution.
Run deployed consumers serially, verify the rebuilt CLI, and perform an adversarial standards review.

The failure-stage regression reproduces false completion against the previous packaged executor.
The corrected implementation stops before dependents and propagates the publication failure.
Receipt checks permit redacted payloads while requiring matching event identity, type, version, and a nonnegative sequence.
The adversarial pass traced start, child failure, terminal publication, and the uninstrumented path.
Review identified a missing completion-publication fallback; the corrected executor attempts the chain failure path
before returning the original anomaly.
Regression coverage includes both successful and failed fallback publication.

Completed validation:

- All three deployed workflow consumers passed serially in 1m35.
- Focused regressions passed 8 tests and 37 assertions, including real quiescence and redaction.
- Isolated packaged regressions passed 28 tests and 160 assertions without production source overlays.
- Final CLI build: 39,128,901 bytes, including the corrected public API documentation.
- Lint and namespace-stratum checks passed; changed-line standards scan found zero violations across 4,345 files.

After the completion-failure review correction:

- Three deployed consumers passed serially in 1m28.
- Rebuilt CLI: 39,128,990 bytes; isolated packaged regressions passed 29 tests and 162 assertions.
- Focused regressions passed 9 tests and 39 assertions; lint, strata, and the changed-line standards scan remain clean.

Final standards verification explicitly uses `standards/miniforge` and the PR merge-base, superseding the earlier
default-path scan results.
It reports zero violations across 4,348 files after fixing missing headers in this PR document and three related
implementation documents.

The next overview-only review identified a child UUID that existed internally but was lost from the returned anomaly.
Failed step publications now preserve the allocated UUID in canonical diagnostic data or legacy domain context,
without replacing the original anomaly provenance.
Coverage exercises all three step publication boundaries with canonical anomalies, legacy anomalies, and rejections.
Focused tests pass 10 tests and 72 assertions; the three deployed consumers passed in 1m25.
The rebuilt CLI is 39,129,057 bytes; isolated packaged verification passed 31 tests and 200 assertions.
The explicit-pack changed-line scan remains clean across 4,348 files.

A subsequent overview review found that the completion-publication fallback falsely named the last successful child.
The failure emitter now takes explicit step attribution: actual step failures retain their step, while a
run-completion publication failure has no failed-step identity.
Regression assertions verify both cases and preserve the original error when the fallback itself fails.
Final attribution validation: consumers passed in 1m22, focused tests passed 10/77, and isolated packaged tests
passed 31/205 against the 39,129,061-byte CLI. Lint, strata, and explicit-pack scanning are clean.

The final lifecycle audit extends the shared run-failure fallback to failed start publication.
Start and completion failures both preserve the original anomaly if the fallback cannot be published,
and neither attributes the run-level failure to a child.
The receipt audit also requires the acknowledged workflow reference and legacy sequence to match the attempted event.
Regressions cover all lifecycle boundaries, fallback unavailability, and mismatched event/child/sequence identities.
Step-completion publication failure likewise attempts a step-failure terminal before failing the chain.
Terminal publication has its own small namespace; child execution owns execution and identity, not fallback construction.
Shared identity attribution preserves the actual failed child when publishing the chain failure also fails.
Consolidated validation: focused tests passed 12/90, three consumers passed in 1m24, and isolated packaged tests
passed 33/218 against the 39,129,887-byte CLI. Lint, strata, and explicit-pack scanning remain clean.

## Deployment Plan

Preserve existing legacy event formats and the optional uninstrumented execution path.
This does not make the legacy stream durable or fix its attempt-time sequence allocation.
Current-profile runtime wiring, durable retry/recovery, and full terminal-event reconciliation remain separate work.

## Related Issues/PRs

Base branch: `main`, after #2024.
Independent of the open #2026 admission-retention storage API.

## Checklist

- [x] Implementation and regression tests
- [x] Serial consumers, packaged verification, and standards scan
- [x] Adversarial review and signed normal hooks
- [ ] Exact-head clean review and all checks before merge
