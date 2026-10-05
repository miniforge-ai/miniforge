<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: durably publish current event profiles

## Overview

Connect typed draft preparation to durable journal acknowledgment and ordered delivery.

## Motivation

N3 §9 requires durable recording before dependent execution. Draft construction
and schema validation alone do not satisfy that contract.

## Layer

Publication integration, branched from main `cfc9bce3` after #2014. Depends on
typed publication admission #2015, now merged and integrated from `e0ba7445`.

## Changes in Detail

- Add an explicit current-profile stream owning an existing canonical journal directory.
- Validate and redact drafts before durable commit; return the journal's exact receipt.
- Restore validated history and retry identities without redelivering recovered events.
- Release ownership after rejected recovery; never fall back to volatile storage.
- Isolate ordinary listener/filter/logging failures after commit while preserving critical causes.
- Query by authoritative scope; workflow cross-references do not imply membership.
- Reject legacy quiesce/drain calls on current streams instead of returning false success.
- Reject closing a legacy stream through the current API without mutating its state.
- Reject unreadable creation/query options without downgrading fatal or interruption causes.
- Detach mutable dates in receipts, history results, and each listener's view.

Supported writes remain chain v2, intervention v2, and Spec snapshots only.
Legacy stream routing is unchanged. Current streams default to no listeners;
their configured sinks are best-effort consumers, not the durable storage port.
Recovered history preserves per-scope sequence order; no global cross-scope
commit order is inferred from independent sequence counters.

## Testing Plan

Regressions cover rejection before storage, commit-before-delivery, retry identity,
per-scope positions, and closed streams. They also cover resource ownership, real disk
recovery, invalid recovered profiles, authoritative queries, and legacy-control rejection.
All four deployed event-stream consumer suites passed serially in 1 minute 23 seconds.
The real miniforge project's JVM disk integration passes 2 tests / 16 assertions.
The CLI built to 39,124,397 bytes. Its isolated packaged API, with test paths but no
source overlays, passes 18 tests / 94 assertions. Lint and truthful strata checks pass.
The incremental standards scan reports zero violations across 4327 files.

The adversarial pass reused the existing stream-state constructor and shared test
factories rather than duplicating maps. It caught legacy anomaly destructuring,
redaction of storage-assigned sequence metadata, and cross-reference scope leakage.
It also caught false success from legacy barriers, mixed-profile close mutation, unreadable sorted-map
options, and mutable-date aliases in public views.
Views preserve trusted sequence counters without treating them as redactable payloads.
Schema data, boundary validation, storage,
publication, and delivery remain separate. Run JVM consumers and hooks serially.

## Deployment Plan

This is publication infrastructure for the reconciled profiles. Runtime writer
migration, lifecycle authority, history migration, and complete scope APIs remain
separate work. Do not claim global spec conformance from this incremental change.

## Related Issues/PRs

Builds on #2011 and #2014 plus the typed admission boundary and merged journal primitives.
Preserve the older acknowledgment worktree and its drafts.

## Checklist

- [x] Implement and validate durable publication integration.
- [x] Complete standards, consumer, and artifact gates.
- [ ] Complete normal signed-hook gates.
- [ ] Require fresh no-findings review and all CI before merge.
