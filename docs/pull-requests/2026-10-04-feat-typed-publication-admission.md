<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: validate typed publication admission

## Overview

Prepare chain v2, intervention v2, and Spec snapshot drafts for durable publication
by validating envelope and typed payload contracts before sequence allocation or storage.

## Motivation

N3 requires semantic admission, not merely a generic envelope check. Chain and
intervention v2 profiles must not silently accept historical payloads.

## Layer

Publication boundary policy, branched from main `ce5f7d2e` after #2011.
Existing chain/supervisory contracts and authoritative scope policy are merged.
The typed chain draft PR is a sibling dependency for constructor integration tests.

## Changes in Detail

- Expose `prepare-current-publication`: a validated, redacted `[scope draft]`, not a receipt.
- Reject caller positions, bare timestamps, unsupported families, and malformed profiles.
- Preserve upstream anomalies and convert redactor exceptions into failures.
- Reject redaction that rewrites event identity, domain identities, or scope.
- Share supervisory fixtures with existing schema tests instead of copying maps.

Other snapshot families are intentionally not admitted by this API yet. The legacy
publisher and historical readers remain unchanged; there is no generic-envelope
fallback that could admit unvalidated payloads through this new boundary.

## Testing Plan

Initial boundary/schema tests pass 10 tests / 204 assertions using the pending
chain fixture dependency. Kondo and truthful strata checks pass. Deployed consumers,
CLI build, packaged API, adversarial review, and normal signed hooks remain pending.

## Deployment Plan

Expose a preparation boundary only. Durable commit/delivery wiring follows in a
separate PR; this change alone is not a publication acknowledgment.

## Related Issues/PRs

Depends on #2006, #2007, #2008, #2011, and #2014. Preserve the older acknowledgment worktree draft.

## Checklist

- [ ] Implement and test boundary composition.
- [ ] Adversarial standards review and deployed validation.
- [ ] Fresh exact-head review and all CI before merge.
