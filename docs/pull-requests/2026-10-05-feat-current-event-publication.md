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
typed publication admission #2015; merge it before publishing this implementation.

## Changes in Detail

Implementation planning in progress. Require explicit durable storage for the
new supported-profile stream. Retain the legacy stream's existing behavior.
Prevent bypassing admission on the new stream. A returned receipt must correspond
to the exact durable event; listener failures must not undo storage acknowledgment.

## Testing Plan

Exercise rejection before storage, commit-before-delivery, retries, closed streams,
resource ownership, and real disk recovery. Run consumers and hooks serially.
Build and test the packaged API, then complete an adversarial standards review.

## Deployment Plan

This is publication infrastructure for the reconciled profiles. Runtime writer
migration, lifecycle authority, history migration, and complete scope APIs remain
separate work. Do not claim global spec conformance from this incremental change.

## Related Issues/PRs

Builds on #2011 and #2014 plus the typed admission boundary and merged journal primitives.
Preserve the older acknowledgment worktree and its drafts.

## Checklist

- [ ] Implement and validate durable publication integration.
- [ ] Complete standards, consumer, artifact, and signed-hook gates.
- [ ] Require fresh no-findings review and all CI before merge.
