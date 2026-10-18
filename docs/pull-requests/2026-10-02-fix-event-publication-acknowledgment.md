<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Commit event positions at publication

## Dependency and bounded objective

Depends on #1983, starting from `3c601198`. Refresh against main once that
prerequisite settles. Do not refresh the older evidence stack during development.
Replace attempt-time sequence allocation with acknowledged publication, preserving
the quiesce fence, redaction-before-storage and ordered delivery. The decisive
acceptance is the existing OPSV post-actuation recovery regression, not a relaxed
evidence validator or reconstructed event counts.

## Acceptance matrix before implementation

- Constructing or abandoning drafts never consumes a committed position.
- Only the publication receipt is authoritative; callers must use that receipt.
- Invalid/rejected publication, storage failure and unknown outcome do not deliver
  an event or silently consume/reuse a committed position.
- Repeating an identity returns its original acknowledgment without duplicate log
  entries; changed content fails closed.
- Concurrent publications commit in scope order. Delivery must not reorder that
  order, including publication reentered from a subscriber callback.
- Subscriber/filter or best-effort sink failures cannot turn a durable record into
  an emission failure. Redaction precedes the authoritative write.
- Quiesce/in-flight accounting remains correct on success, anomaly and exception.
- Reopen uses recovered positions and preserves acknowledgment identity.
- Scope selection must not collapse unrelated nil-workflow events into one counter
  or infer a different scope merely because an event carries a cross-reference.

## Design checkpoints and remaining scope

Trace the current envelope consumers and non-workflow emission families before
changing the public contract. Default journal ownership across multiple streams,
close/recovery lifecycle, explicit volatile streams, retention/replay and sealed
ranges remain required design work. An opt-in durable adapter alone is not a
claim of default durability or N3 completion. Keep this implementation below the
PR size limit; extract a genuine prerequisite stratum only if necessary.

## Validation discipline

Adversarial standards pass before pushing; complete focused failure/concurrency
coverage first, then affected consumer/build/packaged checks once, serially.
No fixture weakening, fabricated range counts, hook bypass or review-gate bypass.
