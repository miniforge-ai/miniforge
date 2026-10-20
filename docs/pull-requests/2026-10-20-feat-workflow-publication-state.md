<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: make terminal publication refusal authoritative

## Plan and scope

Base: main `ac186fcac`. Domain state-machine policy supporting the N3 fail-closed
publication boundary needed by N7. Add an explicit refusal transition from
terminal workflow states to failed; preserve terminal markers and existing
transitions. Wire it into the decomposed topology from #2066, which must merge
before this PR. The dependency is integrated locally for verification.
The runner admission/persistence integration follows separately after terminal
receipt preservation. No independent success claim from an unacknowledged event.

## Verification

Exercise actual compiled-machine transitions from completed, failed and cancelled;
preserve unrelated transitions and verify idempotent topology decoration. Add
workflow checkpoint/reopen coverage when wiring. Normal budgets, signed hooks,
standards/adversarial review, serial product tests, rebuilt package, fresh review
and all CI remain mandatory. No size exception applies to this work.

The physical checkpoint regression first reproduced the missing transition on a
restored completed run. After wiring, it confirms refusal survives disk
save/reopen as failed while retaining run identity and the completed phase result.
Reachability assertions now allow completed-to-failed but still prohibit reaching
active work. All three workflow consumers passed serially (1m31s); rebuilt CLI
39,198,061 bytes; isolated package 42 tests / 281 assertions passed using only the
jar and workflow/phase test fixtures, without production source overlays.

Adversarial review traced compile, terminal transition, projection, snapshot and
restore. The policy preserves final markers, existing transitions, phase guards
and accounting, and is idempotent. `:publication/fail` is an internal machine
event, not a newly emitted N3 lifecycle event. Runner receipt enforcement is not
part of this domain-policy change.

## Deployment

Normal package rollout; no event type or persisted-state schema migration.
