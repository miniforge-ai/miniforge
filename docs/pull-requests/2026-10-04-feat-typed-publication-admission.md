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

Publication boundary policy, branched from main `ce5f7d2e` and updated through #2014 (`cfc9bce3`).
Existing chain/supervisory contracts and authoritative scope policy are merged.
Constructor integration tests use the merged typed chain draft API.

## Changes in Detail

- Expose `prepare-current-publication`: a validated, redacted `[scope draft]`, not a receipt.
- Reject caller positions, bare timestamps, unsupported families, and malformed profiles.
- Preserve upstream anomalies and convert redactor exceptions into failures.
- Preserve fatal and interruption causes instead of downgrading them into failures.
- Never echo an unvalidated event ID into newly constructed error data.
- Reject redaction that rewrites event identity, domain identities, or scope.
- Share supervisory fixtures with existing schema tests instead of copying maps.
- Reject opaque nested values, including keys and metadata, using the shared redaction domain predicate.
- Guard pack, deployment, and PR references against redaction changes.
- Materialize redacted sequences as concrete lists so durable codecs can accept them.

Other snapshot families are intentionally not admitted by this API yet. The legacy
publisher and historical readers remain unchanged; there is no generic-envelope
fallback that could admit unvalidated payloads through this new boundary.

## Testing Plan

Boundary/schema and redaction tests pass 36 tests / 896 assertions against the merged dependency.
All nine chain types pass from the real public constructor through preparation.
Tests reject missing fields, retired aliases, malformed supervisory records, and unsupported envelope versions.
Failure tests cover canonical/legacy anomalies, redactor exceptions, wrapped critical causes, and sensitive malformed IDs.

The adversarial standards pass separated schema data from boundary effects and
kept validation outside the publication engine. It found and fixed error-data
leakage and swallowed critical causes. Shared fixtures avoid copied entity maps.
Copilot identified opaque nested representations and omitted identity references;
both are covered by regressions. End-to-end tracing also caught redacted lists
becoming deferred sequences, incompatible with durable encoding; they now materialize as lists.
Kondo and truthful strata checks pass. The incremental standards scanner is rerun before push.
All four deployed event-stream/redaction consumer suites passed serially in 1 minute 22 seconds.
The CLI rebuilt to 39,113,714 bytes. Its isolated packaged API passes the same 36 tests / 896 assertions.

## Deployment Plan

Expose a preparation boundary only. Durable commit/delivery wiring follows in a
separate PR; this change alone is not a publication acknowledgment.

## Related Issues/PRs

Depends on #2006, #2007, #2008, #2011, and #2014. Preserve the older acknowledgment worktree draft.

## Checklist

- [x] Implement and test boundary composition.
- [x] Adversarial standards review and deployed validation.
- [ ] Fresh exact-head review and all CI before merge.
