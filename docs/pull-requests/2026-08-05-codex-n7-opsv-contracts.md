<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: add canonical OPSV contracts

## Overview

Adds the pure OPSV foundation component: closed N1/N7 domain schemas, canonical
N6 actuation records, typed validation anomalies, and deterministic Experiment
Pack hashing.

## Motivation

Every later OPSV producer and consumer needs one validated representation before
risk, convergence, workflow, evidence, or actuation behavior can be implemented.

## Layer

Foundations — domain contracts and pure hashing composition.

## Depends on

- #1647 — pending merge

## Changes in Detail

- Define closed Experiment Pack and Operational Policy top-level contracts.
- Define explainable risk, per-criterion verification, and requested/effective
  actuation contracts using the reconciled N6 record shapes.
- Return canonical `:invalid-input` anomaly data for validation failures.
- Hash only validated Experiment Packs through the shared canonical EDN hasher.
- Compose the component into the Miniforge project and root development,
  testing, and conformance aliases.

## Testing Plan

- Run focused OPSV component tests.
- Run Polylith structure, Clojure lint, stratum lint, and the full pre-commit gate.
- Verify all acceptance criteria from `work/n07-opsv-contracts.spec.edn`.

Focused result: 8 tests, 48 assertions, 0 failures, 0 errors. The repository
pre-commit gate also passes, including Polylith structure, staged clj-kondo,
stratum lint, smoke tests, and GraalVM/Babashka compatibility.

## Deployment Plan

Merge as the first N7 implementation stratum. No runtime behavior changes until
later domain and workflow PRs consume the new interface.

## Checklist

- [x] Required fields and closed-key behavior tested
- [x] Risk bounds and factor explanations tested
- [x] Actuation modes and governed-effect correlations tested
- [x] Canonical hash equivalence tested
- [x] Full pre-commit gate passes
