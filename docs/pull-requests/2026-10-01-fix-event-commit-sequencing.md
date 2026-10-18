<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Allocate event sequences at publication

## Scope and motivation

Envelope construction currently consumes sequence numbers before publication.
Failed publication and OPSV deduplication therefore create gaps that correctly
fail N6 evidence-range validation. Repair the publication boundary rather than
inventing counts, renumbering retained events, or weakening the validator.

## Bounded delivery plan

1. Commit foundation (this PR, small): candidate selection, identity-safe retries,
   serialized storage acknowledgment, and fencing after uncertain writes.
2. Durable journal adapter (medium): atomic records, exclusive ownership and
   validated recovery of committed positions. Reuse file-durability primitives.
3. Publisher integration (medium): construction no longer consumes numbers;
   return committed envelopes, preserve quiesce, and migrate draft-returning callers.

Dependencies are foundation → storage → publisher. Build on main; keep downstream
branches unchanged until each prerequisite is stable. These internal primitives
are specifically for the next journal adapter, not a second public event API.

## Acceptance checklist

- Candidate preparation leaves counters unchanged; independent scopes stay independent.
- Acknowledgment advances once; identical retries return the original event.
- Reusing an ID with changed content or scope is rejected without a write.
- Concurrent callers get a contiguous committed order; duplicate callers write once.
- Nil, mismatched or anomalous receipts never advance the counter.
- Exceptions and uncertain storage outcomes fence further writes until recovery.
- Fatal errors propagate; interruptions preserve the interrupt flag.
- Reentrant storage publication is refused rather than allocating the same number.
- The adapter must prove restart continuation, corruption rejection and uncertain-write recovery.
- Integration must pass the existing serial OPSV recovery regression without weakening N6.

Batch the full matrix and adversarial standards pass before a push. Run broad
consumer/build checks once the focused matrix passes; rerun affected tests after
real changes, not while waiting on an unchanged review or CI head.

This focused prerequisite does not by itself claim complete N3 durability,
scope classification, restart/replay, retention, or sealed-range holds. Those
remain separate storage and integration work. No deployment is included.

## Standards and verification

The model, designated `boundary.commit` exception adapter and journal orchestration have separate
namespaces with at most three real strata. Reuse one event fixture and receipt
recorder; construct anomalies and localized diagnostics through shared interfaces.
No counter rewinds, fabricated event links or public API expansion.

Focused JVM and rebuilt packaged tests pass 13 tests / 88 assertions. All four
event-stream consumers pass serially. Direct probes verify that interruption
survives the API boundary; tests capture it before reporting can consume the flag.
Normal hooks pass for the model, acknowledgment and matrix commits. The standards
scan identified the original boundary namespace spelling as outside the recognized
boundary convention; the exception adapter now uses the explicit `.boundary.`
namespace. Final focused JVM/packaged verification also passes 13 / 88;
the root scan covers 4,213 files with zero violations. Normal hooks,
current-head review and all CI remain mandatory merge gates.

Adversarial trace: a fresh candidate gets zero without mutation; storage sees that
candidate while the counter remains unchanged; only an exact receipt installs it.
Retry returns the stored envelope without another write. Changed identity content
or scope is refused. Uncertain receipt/throw fences the journal before releasing
the lock. Critical causes propagate after fencing and the in-flight marker is
always removed. Concurrent calls share the same state lock; same-thread storage
reentry is refused. A pre-interrupted caller never reaches storage.

This PR does not close N3.EF.4 in the running product. The durable adapter and
publisher integration must still pass restart and OPSV recovery acceptance.
