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

Event construction, branched from main `532bf711`. Draft assembly depends on
PR #2011, which must merge before the constructor is committed and published.
The independent schema/fixture prerequisite does not need that draft API.

## Changes in Detail

- Reject retired `:chain/id` in current-write payloads without changing historical readers.
- Share independent contract fixtures between schema and constructor tests.
- Validate typed payloads before constructing event identity.
- Preserve supplied run and child identities; do not invent them during construction.
- Protect envelope fields from payload injection and retain extension fields.

## Testing Plan

Twenty-seven assertions exposed acceptance of the retired alias, including nil.
The corrected payload suite passes eight tests / 528 assertions.
Complete constructor coverage, serial deployed consumers, packaged validation,
adversarial standards review, and normal signed hooks before publication.

## Deployment Plan

This is a draft-construction API, not execution authority or a committed receipt.
Legacy constructors and historical source records remain unchanged. Do not send
sequence-free drafts through the legacy publisher. Runtime writer migration,
frozen snapshot recovery, and durable publication admission follow separately.

## Related Issues/PRs

Depends on #2011 and the typed payload foundations from #2007/#2008.

## Checklist

- [x] Tighten current-write identities and share fixtures.
- [ ] Implement and validate typed draft construction.
- [ ] Complete consumer/artifact/standards checks and normal signed hooks.
- Require fresh no-findings review and every CI gate before merge.
