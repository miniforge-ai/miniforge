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

Regressions cover every lifecycle boundary, unavailable fallbacks, quiescence, redacted receipts, and successful execution.
The initial failure-stage regression reproduced false completion against the previous packaged executor.
Receipt checks permit redacted payloads while requiring matching event identity, type, version, workflow reference,
and the assigned nonnegative legacy sequence.

Review corrections and the adversarial transition audit verify:

- Failed start and completion publications attempt the shared chain-failure fallback without blaming a successful child.
- Failed step completion attempts a step-failure terminal before failing the chain.
- Unavailable fallbacks preserve the original anomaly, including its canonical or legacy provenance.
- Actual failed-child identity survives step publication failure and failure to publish the chain failure.
- Terminal publication is isolated from child execution in a small, stratified namespace.
- Real durable admission remains readable during failed start publication, and no child loads afterward.

Final validation after integrating merged admission-retention work:

- Three deployed consumers passed serially in 1m24 before the documentation-only integration conflicts.
- Focused publication regressions passed 12 tests and 90 assertions.
- Combined real-disk integration passed 4 tests and 23 assertions.
- Rebuilt integrated CLI: 39,134,594 bytes.
- Isolated packaged admission, retention, and publication regressions passed 46 tests and 270 assertions.
- Lint and namespace-stratum checks passed.
- Explicit `standards/miniforge` scanning against the PR merge-base found zero violations across 4,359 files.

The explicit-pack scan supersedes earlier default-path scans; missing headers in related implementation documents are fixed.

## Deployment Plan

Preserve existing legacy event formats and the optional uninstrumented execution path.
This does not make the legacy stream durable or fix its attempt-time sequence allocation.
Current-profile runtime wiring, durable retry/recovery, and full terminal-event reconciliation remain separate work.

## Related Issues/PRs

Base branch: `main`, including merged #2024, #2026, and #2028.
Integration preserves both durable-admission and publication-anomaly API contracts.

## Checklist

- [x] Implementation and regression tests
- [x] Serial consumers, packaged verification, and standards scan
- [x] Adversarial review and signed normal hooks
- [ ] Exact-head clean review and all checks before merge
