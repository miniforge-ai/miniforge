<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: validate chain lifecycle payload contracts

## Overview

Define the current-write payload schemas for all nine chain lifecycle events.
This foundation does not switch live publication or reinterpret historical journals.

## Motivation

Scope routing alone cannot enforce the required run identities, resolved versions,
child-run references, counts, and failure taxonomy in N3 §3.12.1.

## Layer

Schema foundations, branched from main at `9000a049`.
Contract PR #2000 must merge before this PR opens.
Scope policy is a sibling foundation; publication admission will compose both.

## Changes in Detail

- Share identity and lifecycle field schemas without duplicating event maps.
- Enumerate exact payload types and version-2 current-write profiles.
- Reuse the canonical failure taxonomy through its component interface.
- Keep envelope validation and stateful lifecycle consistency separate.

## Testing Plan

Exercise every event type, missing required keys, malformed identities and versions,
negative counts, unknown failures and event types, and forward-compatible extra fields.
Run focused tests, deployed consumers, packaged checks, normal signed hooks,
adversarial standards review, exact-head review, and all CI before merge.

The focused suite passes 8 tests / 456 assertions. All four deployed consumers
pass serially, including this suite. Kondo reports zero warnings and errors;
stratum lint makes no changes. The payload contract deliberately does not claim
to prove snapshot immutability, lifecycle ordering, or successful durable publication.
The rebuilt CLI passes the same 8 tests / 456 assertions from outside the checkout,
with no source overlay. The incremental standards scan reports zero findings.

## Deployment Plan

No producer cutover. The subsequent admission boundary will compose these schemas
with the envelope and scope policy after producer migration.

## Related Issues/PRs

Follows approved contract reconciliation in #2000 and precedes live publication.

## Checklist

- [x] Implement contract schemas and regressions.
- [x] Verify consumers and packaged artifact serially.
- [ ] Complete adversarial standards review and normal signed hooks.
- Require clean exact-head review and all CI before merge.
