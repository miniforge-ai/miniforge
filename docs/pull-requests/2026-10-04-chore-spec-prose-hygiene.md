<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# chore: repair prose lint in core specifications

## Overview

Repair existing sentence-length and single-item-list violations in four spec files.
The normal whole-file hook exposed these while preparing the approved contract amendment.

## Motivation

These repairs are separated to keep the contract amendment below its PR budget.
No lint setting, hook, or budget override is used.

## Layer

Documentation hygiene. Base branch: `main` at `6b3c1336`.
No prerequisite PRs. The chain/supervisory amendment follows this PR.

## Changes in Detail

Split overlong sentences and fold single-item lists into prose. Preserve existing
requirements and historical meaning. No new event, scope, identity, or lifecycle
contract is introduced here.

## Testing Plan

Run plainspeak and Markdown lint on every touched file. Review each edit for
semantic equivalence, then run normal signed hooks and all required CI checks.

Normal signed hooks passed for all three commits, including Polylith checks,
360 smoke tests / 1,389 assertions and 8 compatibility tests / 692 assertions.
Exact-head review and CI remain merge gates, not runtime conformance evidence.

## Deployment Plan

Documentation only. No runtime behavior or data migration changes.

## Related Issues/PRs

Prerequisite for the approved chain/supervisory contract reconciliation.

## Checklist

- [x] Preserve existing requirements and historical meaning.
- [x] Pass prose and Markdown checks.
- [x] Pass normal signed hooks.
- Require clean exact-head review and all CI checks before merge.
