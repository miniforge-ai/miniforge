<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: construct validated v2 chain event drafts

## Overview

Construct the nine current-write chain lifecycle and edge payloads with explicit
run identity and resolved definition version, without allocating a publication position.

## Motivation

N3 §3.12.1 separates chain runs, definitions, steps, and child workflow UUIDs.
N2 §14.4 retires the ambiguous chain identity alias for new writes.

## Layer

Event construction, branched from main `532bf711` and updated through the
merged draft dependency #2011 (`ce5f7d2e`).

## Changes in Detail

- Reject retired `:chain/id` in current-write payloads without changing historical readers.
- Share independent contract fixtures between schema and constructor tests.
- Validate typed payloads at the public boundary before constructing event identity.
- Preserve supplied run and child identities; do not invent them during construction.
- Protect envelope fields from payload injection and retain extension fields.

## Testing Plan

Twenty-seven assertions exposed acceptance of the retired alias, including nil.
The schema and public constructor suites pass 11 tests / 641 assertions, covering
all nine types, required-field omissions, injection, unchanged stream state, and
canonical/legacy allocation failures. All four deployed event-stream consumer
suites passed serially in 2 minutes 23 seconds. The CLI rebuilt to 39,110,317 bytes;
the isolated packaged API passed the same 11 tests / 641 assertions. The incremental
standards scan reported zero violations across 4307 files.

The adversarial standards pass traced validation before allocation and moved it
to the interface. It verified nil options and run-level workflow IDs.
All nine types share one fixture factory and one assembly path.
No duplicate per-type envelope maps, interior revalidation, or hidden publishing
effects were introduced. Kondo and per-file strata checks pass.

## Deployment Plan

This is a draft-construction API, not execution authority or a committed receipt.
Legacy constructors and historical source records remain unchanged. Do not send
sequence-free drafts through the legacy publisher. Runtime writer migration,
frozen snapshot recovery, and durable publication admission follow separately.

## Related Issues/PRs

Depends on #2011 and the typed payload foundations from #2007/#2008.

## Checklist

- [x] Tighten current-write identities and share fixtures.
- [x] Implement and validate typed draft construction.
- [x] Complete consumer/artifact/standards checks.
- Run normal signed hooks, then require fresh no-findings review and every CI gate.
- Require fresh no-findings review and every CI gate before merge.
