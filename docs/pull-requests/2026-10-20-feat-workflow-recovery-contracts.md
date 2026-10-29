<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: admit specified workflow recovery event contracts

## Plan and scope

Foundation, based on main `35625de47`. Implement typed current-journal admission
for the checkpoint, machine snapshot, resume and spec-drift events already
specified by N3 §3.21. No new event vocabulary or heartbeat amendment.
Producer and runner recovery migration follow separately. Preserve workflow
scope, authoritative identity, durable replay and sequence integrity on refusal.

## Verification plan

Exercise each required payload field, canonical failure classes, durable receipt
and reopen, duplicate publication and invalid-input sequence preservation.
Use normal budgets, adversarial standards review, serialized consumer tests,
rebuilt isolated package verification, signed hooks, fresh review and all CI.

## Verification results

The prior package reproduced six failures / 53 assertions for these unsupported
profiles. The implementation passed all four event-stream consumers serially
(1m40s), rebuilt CLI 39,198,541 bytes, and isolated packaged recovery/workflow
admission tests: 14 tests / 234 assertions, with test fixtures only.
Kondo reports no warnings/errors; inferred strata stay within three.

Adversarial review traced profile selection, shared workflow identity, canonical
failure classes, provenance redaction refusal, sequence allocation, journal
close/reopen and duplicate publication. Invalid writes and the subsequent valid
write use the same workflow scope, so the no-reservation assertion is meaningful.
Paths, hashes, state/phase, skipped phases and byte counts cannot be silently
redacted into different recovery facts. No producer behavior is changed yet.

## Deployment

Normal package rollout. This adds an explicit current-write profile, not a
generic permissive fallback. Existing unsupported event families remain refused.
