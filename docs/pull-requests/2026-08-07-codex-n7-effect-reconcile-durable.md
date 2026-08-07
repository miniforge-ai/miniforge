<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: reconcile only durable unsettled effects

## Overview

Makes reconciliation reload the durable effect transaction before probing an
external system. Stale caller state can no longer reopen an already-settled
effect or cause a probe for a missing or invalid transaction identity.

## Layer

Application service — durable effect reconciliation orchestration.

## Depends on

- #1700 (durable commit authorization and single-claim fencing) — merged

## Strata Affected

- Reconciliation coordinator — resolve the durable record before probing.
- Record vocabulary — centralize the lifecycle-position value shape.
- Tests — decompose reconciliation outcomes into single-purpose cases.

## Motivation

Reconciliation previously trusted the lifecycle state in a caller-supplied
record. A stale value could therefore probe an effect whose durable record had
already settled. Durable storage must remain the authority for lifecycle
decisions on both commit and reconciliation paths.

## Changes in Detail

- Reload reconciliation candidates by their validated durable effect ID.
- Refuse missing, invalid, and already-settled durable records before probing.
- Preserve compare-and-set recording of valid probe answers.
- Centralize lifecycle-position maps used by commit and reconciliation.
- Replace one multi-scenario reconciliation test with focused tests and shared
  factories.

## Testing Plan

- Test durable state overriding a stale caller state without probing.
- Test missing and invalid durable identities without probing.
- Test matched, mismatched, unreadable, throwing, and marker-shaped answers.
- Test restart recovery from the durable `:committing` state.
- Run effect-transaction tests in every composing project.
- Run Poly, Kondo, stratum lint, compliance review, and full pre-commit.

## Deployment Plan

Merge to `main`; this changes reconciliation lookup semantics and requires no
record migration.

## Related Issues/PRs

- Completes `work/ariadne-effect-transaction-fencing.spec.edn`.

## Checklist

- [ ] Reconciliation reloads the durable transaction
- [ ] Stale settled records are refused without probing
- [ ] Missing and invalid transaction identities are refused without probing
- [ ] Probe outcomes preserve the honest unknown/reconciled contract
- [ ] Poly reports zero errors and zero warnings
- [ ] Standards and pre-commit gates pass
