<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: gate configured chain execution on durable admission

## Overview

Connect chain admission to immutable retention before lifecycle execution when a retention directory is configured.

## Motivation

The storage API alone does not prevent execution from starting before its composition is durable.
A failed, unsafe, or conflicting admission write must prevent the executor from starting.

## Changes in Detail

- Recognize the explicit `:chain/retention-directory` execution option.
- Retain the admitted record before invoking the chain executor.
- Propagate retention failure without events or child execution from that executor.
- Reuse the validated record without allocating a second identity or repeating schema validation.

An explicitly present but invalid directory is an error, not permission to use the legacy path.
Public persistence still validates caller-supplied records; internally admitted records reuse their established contract.

## Testing Plan

Verify publication ordering, failure short-circuiting, stable identity, and the explicit legacy path.
Exercise the execution gate with real immutable storage and a conflicting definition version.
Run consumers serially, verify the packaged CLI, and review against Miniforge standards.

Validation completed:

- Focused admission and retention tests: 18 tests, 82 assertions.
- All three deployed workflow consumers passed serially in 1m21.
- Real-disk integration: 3 tests, 17 assertions, including readback inside dispatch and immutable-version conflict.
- Rebuilt CLI: 39,132,659 bytes; isolated packaged tests passed 34 tests and 180 assertions.
- Lint and namespace strata are clean; explicit-pack changed-line scan reports zero violations across 4,353 files.

The adversarial pass traced definition publication, invocation acknowledgment, and executor dispatch in order.
It verified that a present invalid directory cannot select the legacy path and that admission is not repeated internally.
The storage format and persistence path remain shared; the disk-test callback was extracted into a named function.
Both retention PR documents include the required copyright headers.

## Deployment Plan

Callers enabling this path must provide an existing canonical directory under exclusive host control.
The absent-option path remains legacy and does not claim durable recovery conformance.
CLI/current-profile migration must configure retention before removing that compatibility path.
This does not restore execution-machine state or authorize resuming an old invocation.

## Related Issues/PRs

Base branch: `main`, after merged #2026.
Independent of #2027's publication-failure propagation.

## Checklist

- [x] Implementation and contract tests
- [x] Serial consumers and disk integration
- [x] Packaged verification and standards scan
- [x] Adversarial review and signed normal hooks
- [ ] Exact-head clean review and all checks before merge
