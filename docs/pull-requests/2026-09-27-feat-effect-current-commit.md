<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Commit the durable effect under current authority

## Overview

Add a class-1 internal transaction entry point for runtime-owned grant lookup
and execution of the claimed durable proposal. Keep the legacy API compatible.

## Motivation

OPSV must execute the payload that its grant authorizes. The legacy commit API
reloads the durable proposal but accepts a zero-argument callback and a grant
snapshot. Its caller can capture stale authority or a different payload.

## Layer and dependencies

Foundation, after merged PRs #1910 and #1911. The next application PR will use
this entry point for OPSV PR execution. Provider adapters follow that layer.

## Changes in detail

- Resolve the named grant from trusted runtime state at commit time.
- Pass the claimed durable record to the effect callback.
- Refuse missing, malformed, mismatched, revoked or expired authority.
- Retain atomic claim, honest unknown outcomes and duplicate protection.
- Leave existing callers and the legacy API unchanged.

## Testing plan

Exercise first use, a stale candidate, changed scope, authority failures,
duplicate commits, concurrent claims and uncertain provider outcomes.
Run component tests, Polylith checks, lint, hooks and the standards scan.

## Deployment plan

No production caller changes in this PR. The next OPSV slice opts into the new
entry point. Rollback removes that opt-in without changing persisted records.

## Related work

N7 sections 5.4 and 7.2; the governed-actuation work item; PRs #1910 and #1911.

## Checklist

- [ ] Implement and test the runtime commit boundary.
- [ ] Complete the adversarial standards review and local checks.
- [ ] Settle PR comments and pass CI before merge.
