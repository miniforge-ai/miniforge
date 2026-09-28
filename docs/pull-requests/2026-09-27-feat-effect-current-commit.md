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

Local results: 57 tests and 203 assertions pass in each of Miniforge, Core
and TUI. The new boundary has 12 tests and 80 assertions. Component scanning
reports zero findings across 18 files. The full scan retains 11 earlier
findings outside this component. Kondo reports no errors or warnings.

## Adversarial review

The first-run path reads the durable UUID, resolves its recorded grant ID,
then reads the runtime clock. Authorization uses the durable scope and a count
of one. The atomic claim compares the full loaded record. Only its winner
passes the claimed record to the executor. Tests compare that record with a
fresh disk read inside the callback.

Missing or unreadable authority leaves the proposal available for retry.
Known denial records failure without an effect. A thrown or unreadable effect
response remains unknown and cannot be retried as a fresh commit. The legacy
wrapper keeps its zero-argument callback and existing authority semantics.

Runtime ports are trusted process dependencies, not model-provided values.
Delegated grants fail closed because this API does not resolve ancestry.
Grant lookup is a commit-time recheck, not a lock across the provider call:
runtime cancellation and the OPSV adapter still need their own safe boundary.
This PR does not claim to complete emergency-stop integration or PR emission.

## Deployment plan

No production caller changes in this PR. The next OPSV slice opts into the new
entry point. Rollback removes that opt-in without changing persisted records.

## Related work

N7 sections 5.4 and 7.2; the governed-actuation work item; PRs #1910 and #1911.

## Checklist

- [x] Implement and test the runtime commit boundary.
- [x] Complete the adversarial standards review and local checks.
- [ ] Settle PR comments and pass CI before merge.
