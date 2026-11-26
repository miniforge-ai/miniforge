<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# test: A refused acquire never mutates the provider

## Overview

Test-only PR for issue #2506. One acceptance row says a changed request for an existing key is
rejected without a provider mutation. The code refuses a changed request in `claim!`, before any
provider call, and a forged tenant in admission, before preflight. The existing tests assert only
the refusal and that no namespace is live. This test counts the provider's mutating calls and
asserts none follow either refusal.

API surface class 1: in-process EDN.

## Layer

Application tests only. No production code changes.

Base branch: `main`. Depends on nothing open.

## Motivation

While checking #2506's acceptance rows against merged code, the request-key row was covered by
refusal tests but not by evidence that the provider is left untouched. A refusal reached after a
provision would pass the existing tests. This closes that evidence gap.

## Changes in Detail

1. `ledger_lifecycle_test`:
   1. `mutating-calls`: the provider calls that change what it holds. `probe` and `observe` only
      read, and preflight probes before the claim, so a refused request may still make them.
   2. `test-a-refused-request-never-mutates-the-provider`: after one acquire, a changed request for
      the same key is refused as `conflicting-request`, and a request naming another tenant as
      `forged-tenant`. The mutating call counts are unchanged after each.

## Testing Plan

1. The new test passes on `main`.
2. With `acquire-decision` changed so a changed request decides `:new`, the test fails on three
   assertions: the refusal and both unchanged-count checks. Checked by mutation.
3. clj-kondo and stratum-lint are clean.

Fake-provider evidence only. Receipts stay `:profile/unqualified`.

## Deployment Plan

Nothing deploys. Test-only change in an opt-in component.

## Related Issues/PRs

Issue #2506; parent tracker issue #2439.

## Checklist

- [x] Zero provider mutations on a conflicting or forged-tenant acquire, with a mutation check
- [ ] CI green, Copilot threads resolved, squash-merged
