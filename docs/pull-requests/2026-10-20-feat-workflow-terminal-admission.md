<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: enforce terminal publication admission

## Plan and scope

Application layer, based on main `48afacd9e`. Depends on merged receipt
preservation #2067 and the forthcoming authoritative publication-state policy
(which depends on #2066). Admit the terminal receipt before returning and
checkpointing the final runner outcome. Refusal must become a failed machine
state, not just a contradictory projected status. Preserve nil/legacy and
explicit skip-lifecycle behavior, original phase facts, and cleanup.

## Verification plan

Trace actual runner ordering and cleanup. Test acknowledged completion,
publication refusal, physical checkpoint/reopen, and compatibility paths.
Keep auxiliary notification failures distinct from terminal refusal.
Use normal commit/PR budgets, adversarial standards review, serial consumer
tests, rebuilt isolated package tests, signed full hooks, fresh exact-head review
and all CI including Build. No size exception applies.

## Verified implementation

The runner delegates finalization to a three-stratum internal namespace.
Terminal refusal appends a typed error without losing earlier failures, applies
the authoritative refusal transition, and regenerates output status before the
final checkpoint. Cleanup receives execution facts even if publication is fatal.
The startup regression now selects its original refusal by type because a closed
journal can legitimately refuse both startup and terminal failure publication.

The old packaged runner reproduced eight failures / 35 assertions after the
test fixture was corrected to reach the terminal boundary. Shared legacy phase
completion publication is isolated in that test; terminal event construction,
journal acceptance/refusal, checkpoint save/reopen, and cleanup remain real.
All three consumers passed serially (1m31s), rebuilt CLI 39,199,111 bytes, and
isolated packaged regressions passed 43 tests / 290 assertions with test fixtures
only, no production source overlays. Nil/legacy and explicit skip behavior remain
covered. Adversarial review traced receipt ownership, output projection,
checkpoint ordering, retained phase facts and the fatal-error cleanup path.

This does not yet make checkpoint I/O errors explicit or deduplicate terminal
republication across a process restart; those remain separate durability work.

## Deployment

Normal package rollout after the state-policy dependency merges.
No new emitted event type. No claim of N7 completion from this slice alone.
