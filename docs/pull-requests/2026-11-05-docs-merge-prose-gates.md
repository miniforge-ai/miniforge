<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: Repair prose findings in incoming PR records

## Overview

Documentation-only cleanup, based on main. No prerequisites.
The installed prose hook rejects incoming PR records during the N7 issuer merge.

## Motivation

Keep the prose gate enabled without mixing unrelated records into the issuer PR.

## Changes in Detail

Split long sentences and fold single-item lists into prose.
Preserve historical claims, identifiers, evidence, and code examples.

## Testing Plan

The installed prose linter passes on all 17 edited records and this document.
The owner reviewed the complete diff against the original records.
Use normal signed hooks, CI, settled review, and a fresh full review before merging.
No code, configuration, or executable tests change.

## Deployment Plan

No runtime changes or live effects.

## Related Issues/PRs

Unblocks the approved N7 issuer integration after scope foundation #2392.

## Checklist

- [x] Prose checks and whitespace checks pass.
- [ ] Owner review, CI, and full current-head review pass.
