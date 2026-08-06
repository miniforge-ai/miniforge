<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: align OPSV governed effects with Ariadne

## Overview

Replace OPSV's stale N10 intent/OIR/capability evidence with the accepted
Ariadne runtime identities: EffectTransaction, ExecutionGrant, and
DecisionEnvelope.

## Motivation

N7's actuation contract still referred to execution objects that the accepted
Ariadne adoption RFC superseded and that production code does not implement.
Building against those fields would either create a second authority model or
encourage fabricated identifiers. This change makes the evidence contract
truthful before external mutation is added.

## Layer

Normative governed-effect and evidence contracts.

## Depends on

- PR #1680 (deterministic OPSV staging adapter) — merged

## Changes in Detail

- Establish the Ariadne adoption profile in N10 and make it authoritative over
  the retained legacy design material.
- Correlate each governed effect by effect transaction, execution grant, and
  allow decision envelope UUID.
- Replace aggregate capability references with execution-grant references.
- Synchronize the closed OPSV, N3 event, and N6 evidence schemas and fixtures.
- Make production grant issuance an explicit prerequisite for N7 actuation.

## Standards Audit

- The spec and all three closed runtime schemas use one correlation vocabulary;
  there is no compatibility shim or duplicated legacy/current map shape.
- Evidence finalization derives grant correlation from governed effects and
  reports a domain-specific mismatch value.
- Changed functions remain small, value-oriented, and in their existing strata;
  no namespace or component dependency is added.
- The compliance scanner reports no finding in a changed file. Its 79 findings
  are the existing repository baseline.
- clj-kondo and computed-stratum checks are clean. Polylith reports only the
  four existing workspace warnings.

## Testing Plan

- Focused OPSV/event/evidence tests: 23 tests, 175 assertions.
- Full `evidence-bundle` brick tests across three projects: green.
- Full `opsv` brick tests across two projects: green.
- Full `event-stream` brick tests across four projects: green.
- Pre-commit smoke: 339 tests, 1,285 assertions.
- GraalVM/Babashka compatibility: 8 tests, 606 assertions.
- Miniforge CLI uberjar builds successfully.

## Deployment Plan

No deployment action is required. This is a contract migration; OPSV remains
recommend-only and emits no external effects.

## Checklist

- [x] N3, N6, N7, and N10 agree on Ariadne effect correlation.
- [x] Runtime schemas reject the superseded evidence keys.
- [x] Evidence finalization requires every effect's grant to be referenced.
- [x] The actuation work item forbids unenforced authority.
- [x] Grant issuance is named as a prerequisite rather than simulated in OPSV.

## Follow-up

Implement the Ariadne grant-issuance prerequisite, then build OPSV PR and apply
effects on the real grant and effect-transaction path.
