<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Read-only verification preparation boundary

## Overview

Add the adapter boundary for a fresh, correlated target observation before
candidate mutation or load. This is preparation, not execution authority.

## Motivation

A retained comparison contract must not authorize effects against a target that
already drifted. Guarded-runner admission needs a distinct read-only preparation
step before retaining the contract and invoking verification.

## Changes in Detail

- Add an explicit read-only preparation port and typed correlated receipt.
- Share verification identity and receipt vocabulary without changing legacy behavior.
- Extend functional adapters with an explicit preparation callback.
- Keep absent preparation unavailable; never manufacture baseline observations.

Ownership: `phase-opsv`, which owns verification ports and callback adapters.
No new cross-brick dependencies. Both `miniforge` and `miniforge-tui` are affected.

## Testing Plan

Test correlation, input shape, missing preparation, callback validation, and
existing adapter behavior. Run both product contexts, native packaging, normal
hooks, CI, and full-head review.

Focused preparation, verification, and drift coverage passes 22 tests and
131 assertions. Both affected product contexts pass. Fresh isolated CLI
packaging passes 45 tests and 239 assertions without production source overlays.
The receipt extraction preserves existing wire shapes and failure handling.

## Deployment Plan

No native acquisition or live effects. Durable contract retention and the
runner's pre-effect admission use this boundary in a subsequent slice.
The adapter must independently establish the observation's target and membership.

## Related Issues/PRs

Base branch: `main`. Depends on merged #2353, #2354, and #2355.
The private branch used #2355's shared target and replica observation schemas.
Reconciliation against actual main excludes those inherited changes from this
PR's scope and retains all original signed commits.

## Checklist

- [x] Cover the public preparation contract and legacy adapter behavior.
- [x] Complete owner adversarial and applicable-standards review.
- [ ] Pass all product, package, hook, CI, and full-review gates.
