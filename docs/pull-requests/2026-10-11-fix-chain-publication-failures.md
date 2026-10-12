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

## Testing Plan

Exercise failures at every lifecycle boundary, rejected or mismatched receipts, and normal successful execution.
Run deployed consumers serially, verify the rebuilt CLI, and perform an adversarial standards review.

The failure-stage regression reproduces false completion against the previous packaged executor.
The corrected implementation stops before dependents and propagates the publication failure.
Receipt checks permit redacted payloads while requiring matching event identity, type, version, and a nonnegative sequence.
The adversarial pass traced start, child failure, terminal publication, and the uninstrumented path.

Completed validation:

- All three deployed workflow consumers passed serially in 1m35.
- Focused regressions passed 8 tests and 37 assertions, including real quiescence and redaction.
- Isolated packaged regressions passed 28 tests and 160 assertions without production source overlays.
- Final CLI build: 39,128,901 bytes, including the corrected public API documentation.
- Lint and namespace-stratum checks passed; changed-line standards scan found zero violations across 4,345 files.

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
