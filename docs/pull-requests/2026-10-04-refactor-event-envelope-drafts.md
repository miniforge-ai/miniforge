<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: separate event drafts from sequence reservation

## Overview

Share event identity and metadata construction between an uncommitted draft API
and the existing sequence-allocating envelope API.

## Motivation

N3 requires publication positions to be allocated at acknowledged commit, not
when producers construct events. Typed v2 chain writers need a draft constructor
that does not consume positions before admission. This extraction supplies it
without changing the deployed publisher or existing constructor signatures.

## Layer

Event construction, branched from main `424f6359`.

## Changes in Detail

- Add public `create-event-draft`, with no sequence reservation or delivery.
- Share UUID/Snowflake generation and supported identity-option handling.
- Retain legacy `create-envelope` sequencing through one atomic reservation.
- Preserve generator failures as the original anomaly values in either API.
- Prevent options from overwriting protected envelope fields.

## Testing Plan

Focused public draft, core, and identity propagation tests pass 57 tests /
206 assertions. The new tests verify no stream mutation or sequence consumption,
protected fields, and exact canonical/legacy generator anomaly propagation.
Kondo reports zero warnings/errors; truthful strata pass.
All four deployed event-stream consumers pass serially (Data Foundry, Miniforge,
Core, and TUI). The rebuilt CLI passes the same 57 tests / 206 assertions outside
the checkout, using the artifact and test files without source overlays.
The incremental standards scan finds zero violations across 4293 files.
The adversarial pass verifies atomic legacy sequencing, no draft reservations,
supported metadata truthiness, protected fields, and unchanged generator failures.

## Deployment Plan

Existing producers keep their sequence-allocating API. Drafts are not admitted,
redacted, committed, acknowledged, or delivered. Retain a draft for idempotent
publication retries. Do not send a sequence-free draft through the legacy publisher;
the admission/commit/delivery cutover follows separately. Typed chain construction
and live producer migration also remain separate work.

## Related Issues/PRs

Supports N3 commit-time sequencing and the v2 chain migration from PR #2000.

## Checklist

- [x] Share construction and preserve the legacy API.
- [x] Validate deployed consumers and isolated artifact.
- [x] Complete adversarial standards review and normal signed hooks.
- Require fresh no-findings review and all CI gates before merge.
