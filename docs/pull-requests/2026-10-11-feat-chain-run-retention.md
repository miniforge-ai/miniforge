# feat: retain chain admission manifests durably

## Overview

Retain chain invocation identity and resolved composition through the existing immutable artifact store.
This implements the storage boundary needed by N1 §2.32 and N2 §14.4 recovery.

## Motivation

In-memory admission records cannot survive restart or enforce immutable definition versions across invocations.
Recovery must read the retained composition, not resolve a newer definition or allocate another run identity.

## Changes in Detail

- Validate detached admission records at the public storage boundary.
- Publish a deterministic definition manifest before its invocation manifest.
- Reuse artifact publication's immutable identity and durable acknowledgment protocol.
- Require matching manifests on uncached recovery reads.
- Reject excluded values before writing instead of changing executable composition through redaction.

One factory constructs both manifests; the invocation references its definition as a parent.
The deterministic definition key is a storage address, not a historical run identity or proof of authority.
Callers must supply an existing canonical directory under exclusive host control, including its ancestors.

An unconfirmed invocation write may already exist; retry the identical record.
A definition can remain after invocation publication fails, without authorizing execution.
Changing composition under the same definition ID and version fails closed.

## Testing Plan

Test durable round trips, retries, immutable version/run conflicts, corruption, missing records, unsafe values, and
unconfirmed acknowledgments.
Verify recovery neither resolves definitions nor allocates invocation identity.
Run deployed workflow consumers serially, rebuild the CLI, and exercise the packaged boundary without source overlays.

The adversarial pass found and corrected two boundary defects before publication.
Malformed sorted maps are normalized before anomaly inspection can invoke their key comparator.
Manifest comparison now checks type-aware content digests as well as value equality.
A regression reproduced acceptance of a changed numeric binding representation before the fix and refusal afterward.

Completed validation:

- All three deployed workflow consumers passed serially in 1m20 after the final correction.
- Real project disk integration passed 2 tests and 12 assertions.
- Focused development regressions passed 8 tests and 37 assertions.
- Clojure lint reports zero warnings or errors; namespace-stratum checks pass.
- Rebuilt CLI: 39,132,417 bytes; isolated packaged regressions passed 28 tests and 160 assertions without production
  source overlays.
- Changed-line standards scan: zero violations across 4,345 files.

## Deployment Plan

This adds public persistence and loading APIs for admission records only.
It does not resume execution, change legacy event writers, or yet make retention mandatory in the runtime.
Execution-state recovery and acknowledged current-profile lifecycle integration remain subsequent slices.
No historical identities are inferred or migrated.

## Related Issues/PRs

Base branch: `main`, fast-forwarded after merged #2024.
Depends on #2024's resolved admission contract and #2021's distinct child-run identity.

## Checklist

- [x] Implementation and contract tests
- [x] Serial consumer and disk validation
- [x] Packaged CLI verification and standards scan
- [x] Adversarial standards pass and normal signed hooks
- [ ] Exact-head clean review and all checks before merge
