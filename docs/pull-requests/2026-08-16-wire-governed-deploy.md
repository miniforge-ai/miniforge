<!--
  Title: Enforce granted deployment transactions
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(deploy): enforce granted deployment transactions

## Layer

Application flow.

## Depends on

- #1798 — exact deployment authority and fail-closed policy classification.
- #1803 — immutable deployment provider operations.
- #1804 — zero-warning Polylith project declarations.

## Overview

Completes the deployment half of Ariadne step 2d. The live deploy phase now
resolves one Kubernetes target, renders and server-validates one manifest,
evaluates policy and authority, captures rollback state only after permission,
records the proposal durably, rechecks the exact grant at commit, applies the
recorded manifest, and reconciles the provider-observed result.

## Changes

- Replace the direct deployment flow in `enter-deploy` with the governed
  transaction.
- Persist the exact target, rendered manifest, server dry-run output, rollback
  state, effect ID, grant ID, and DecisionEnvelope ID before mutation.
- Re-read the durable proposal for exact-byte apply and reconciliation.
- Keep failed, unknown, mismatched, and reconciled outcomes explicit.
- Preserve rollback evidence in failed phase context.
- Defer rollback reads until deployment authority is permitted.
- Refuse absent, mismatched, expired, or policy-denied authority without
  running the apply operation.

## Design

- Provider mechanics remain in the adapter operation map.
- Authority and policy decisions remain in the deployment domain boundary.
- Durable effect coordination remains in `deploy-transaction`.
- `deploy-governed` is a flat application pipeline of single-purpose steps.
- `deploy-outcome` is a pure projection into the existing phase contract.
- Shared fixtures and factories own repeated target, operation, and authority
  shapes; durable records are asserted from the real store.

## Verification

- `bb lint:clj:all`
- `bb lint:stratum`
- `bb poly:check`
- `clojure -M:poly test brick:phase-deployment`
- `bb pre-commit`
- `bb test`
- `bb review`
- `bb pr-budget`
- Adversarial review against Clojure, function, component, application-flow,
  result-handling, and stratified-design standards.

## Deployment

No schema migration is required. The feature changes the deploy phase from
ambient mutation to fail-closed governed mutation. Existing durable effect
records remain readable; failed apply records retain rollback information for
operator recovery.
