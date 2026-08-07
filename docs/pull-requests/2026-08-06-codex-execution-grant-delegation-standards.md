<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: simplify execution-grant delegation

## Overview

Bring execution-grant delegation and attenuation into compliance with message,
function, Clojure, and stratified-design standards before adding a production
issuer.

## Motivation

The component emitted raw developer-facing strings, repeated the same violation
map shape, and nested parent validation, child assembly, and attenuation policy
inside one public function. Those gaps made the authority path harder to audit
and unsafe to extend merely because its tests were green.

## Layer

Behavior-preserving internal component design.

## Changes in Detail

- Add the execution-grant system-message catalog and translator.
- Replace duplicated attenuation maps with one violation constructor.
- Express scope containment as a direct map-value comparison.
- Separate parent refusal and child assessment from public delegation.
- Preserve the existing public component interface and anomaly contract.

## Architecture

Attenuation remains pure policy. Its real dependency depth is three layers:
axis comparisons and violation construction, aggregate violation discovery, and
the boolean convenience predicate. Grant core assembly and parent/child
assessment remain internal to the same component and feed the existing public
interface.

## Testing Plan

- Focused execution-grant tests in every composed product.
- Poly check, kondo, inferred-stratum lint, smoke tests, and Graal compatibility.
- Full stable-derived test plan before merge.

## Standards Review

- Function design: named parent and child decisions replace nested control flow.
- Data design: one violation constructor replaces repeated map literals.
- Message discipline: all changed emitted text resolves through resources.
- Stratified design: staged lint proves each namespace has at most three inferred
  layers; no annotation masks a deeper call chain.
- Component design: no new public component dependency or interface is exposed.

## Checklist

- [x] Commit budgets pass without overrides.
- [x] Poly, kondo, stratum, smoke, and Graal gates pass.
- [x] Focused execution-grant tests pass in all four composed products.
- [x] Adversarial changed-code review is clean.

## Related Work

- `work/ariadne-grant-issuance.spec.edn`
- Follow-up: breach storage and eligibility standards remediation.
