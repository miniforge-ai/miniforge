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
transitions. Then wire it into the decomposed topology after #2066 merges.
The runner admission/persistence integration follows separately after terminal
receipt preservation. No independent success claim from an unacknowledged event.

## Verification

Exercise actual compiled-machine transitions from completed, failed and cancelled;
preserve unrelated transitions and verify idempotent topology decoration. Add
workflow checkpoint/reopen coverage when wiring. Normal budgets, signed hooks,
standards/adversarial review, serial product tests, rebuilt package, fresh review
and all CI remain mandatory. No size exception applies to this work.

## Deployment

Normal package rollout; no event type or persisted-state schema migration.
