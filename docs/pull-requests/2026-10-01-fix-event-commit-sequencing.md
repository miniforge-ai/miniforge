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

Extract a coherent publication primitive; retain genuine namespace strata and
reuse existing anomaly/message boundaries. Do not add counter-rewind logic.
Test concurrent publication, failures, retries, quiesce, and acknowledged return
values. Run every event-stream consumer and packaged regressions serially, then
rerun the OPSV publication-recovery scenario. Normal hooks, a root standards
scan, bounded commits/PR, fresh review and all CI are required before merge.
