<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: authorize and claim durable effects once

## Overview

Makes commit authorization depend only on the durable proposal and claims that
proposal atomically before invoking an irreversible effect. A stale caller
value, fabricated usage, substituted grant, or concurrent process therefore
cannot invoke the same effect again.

## Layer

Application service — effect commit authorization and claim orchestration.

## Depends on

- #1699 (durable effect identity, create-only persistence, and lifecycle
  compare-and-set) — merged

## Strata Affected

- Authorization evaluator — derive scope and usage from durable effect data.
- Commit coordinator — reload and claim the durable proposal before invocation.
- Tests — shared grant/proposal factories and deterministic claim coverage.

## Motivation

`:constraint/max-count 1` is not a replay fence when a caller can repeatedly
present stale proposal data or choose its own usage count. The durable effect
identity and an atomic lifecycle claim must decide whether invocation is still
permitted.

## Changes in Detail

- Reload the durable transaction before commit authorization.
- Authorize the durable proposal plus its effect ID with `:usage/count 1`.
- Ignore caller-provided scope, state, and usage claims.
- Require the grant identity and effect class recorded by the proposal.
- Atomically claim `:proposed` as `:committing` before invoking the effect.
- Keep JVM failures visible while leaving the durable claim reconcilable.

## Testing Plan

- Test stale-map replay and caller context spoofing.
- Test substituted grant and effect-class refusal.
- Test concurrent commit at-most-once behavior.
- Test a process-level failure after claim leaves `:committing` durable.
- Run effect-transaction tests in every composing project.
- Run Poly, kondo, stratum lint, compliance review, and full pre-commit.

## Deployment Plan

Merge to `main`; existing effect records remain readable and require no data
migration.

## Related Issues/PRs

- Precedes durable reconciliation reload in the N7 transaction-fencing spec.

## Checklist

- [ ] Commit authorization ignores caller scope, state, and usage count
- [ ] The recorded grant identity and effect class are enforced
- [ ] Concurrent and stale commits invoke at most once
- [ ] Claimed transactions remain reconcilable after process death
- [ ] Poly reports zero errors and warnings
- [ ] Standards and pre-commit gates pass
