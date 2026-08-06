<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: decompose effect transaction coordination

## Overview

Bring EffectTransaction into compliance with component, function, message, and
stratified-design standards before extending its authority contract.

## Motivation

The coordinator mixed durable record changes, call outcome interpretation,
grant authorization, execution, and reconciliation in one namespace. Its
declared three strata concealed eight inferred dependency layers. Commit and
reconciliation tests also duplicated proposal and grant maps that had already
drifted to different key vocabularies.

## Layer

Behavior-preserving internal component design.

## Changes in Detail

- Make `core` a thin public facade.
- Separate durable records, wrapped calls, commit authorization, and
  reconciliation into focused namespaces with at most three strata each.
- Move developer-facing messages into the component message catalog.
- Centralize the canonical merge proposal and grant fixtures shared by commit
  and reconciliation tests.

## Architecture

`record` owns validation and atomic state advance. `call` converts injected
effect and probe calls into honest data. `commit` coordinates authorization and
effect execution. `reconcile` asks the external system to settle unknown
outcomes. None of those responsibilities is duplicated by the facade.

The durable proposal is adapted into grant authorization context in `commit`.
The current grant implementation does not inspect that scope yet; the dependent
grant-scope PR activates the contract and supplies its refusal tests.

## Testing Plan

- EffectTransaction tests across every consuming project.
- Full stable-derived test plan across all five projects.
- Poly check, kondo, stratum lint, compatibility tests, and PR budget.

## Standards Review

- Inferred strata: no namespace exceeds three layers.
- Function design: each coordinator stage has one responsibility and named data
  transitions replace the former nested control flow.
- Component design: all new namespaces remain internal to EffectTransaction;
  the public interface is unchanged.
- Data design: one canonical proposal/grant fixture replaces duplicated maps.
- Exceptions-as-data scan: the existing store throws remain intentional at the
  durability boundary; failure to persist the audit record must stop the
  irreversible effect.

## Checklist

- [x] Focused EffectTransaction tests pass in all four consuming projects.
- [x] Full five-project test plan passes.
- [x] Poly, kondo, stratum, compatibility, and pre-commit checks pass.
- [x] PR budget passes at 591/600 reportable lines without override.
- [x] Adversarial standards pass is clean for changed code.

## Related Work

- `work/ariadne-grant-scope-enforcement.spec.edn`
- `work/ariadne-grant-issuance.spec.edn`
