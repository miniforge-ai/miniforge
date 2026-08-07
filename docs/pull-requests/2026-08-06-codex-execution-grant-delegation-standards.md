<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: simplify execution-grant delegation

## Overview

Bring execution-grant delegation and attenuation into compliance with message,
validation-boundary, function, Clojure, testing, and stratified-design standards
before adding a production issuer.

## Motivation

The component emitted raw developer-facing strings, repeated violation and test
request maps, validated inside its core namespace, and nested parent assessment,
child assembly, and attenuation policy inside one public function. Its schemas
also admitted both `Instant` and `Date`, while liveness and attenuation assumed
only `Instant`. Those gaps made the authority path harder to audit and unsafe to
extend merely because its tests were green.

## Layer

Closely related execution-grant foundation, domain, and boundary hardening. The
public API remains stable; malformed boundary values that core previously
coerced are now returned as invalid-input anomalies.

## Changes in Detail

- Add the execution-grant system-message catalog and translator.
- Replace duplicated attenuation maps with one violation constructor.
- Express scope containment as a direct map-value comparison.
- Separate parent refusal and child assessment from public delegation.
- Define issuance, delegation, and revocation boundary schemas.
- Move all Malli validation to the component interface and let core trust data
  that has crossed that boundary.
- Normalize both concrete `inst?` types before expiry comparison or persistence.
- Replace repeated delegation request maps with named test factories.
- Preserve the existing public component functions and anomaly categories.

## Architecture

Attenuation remains pure policy. Its real dependency depth is three layers: axis
comparisons and violation construction, aggregate violation discovery, and the
boolean convenience predicate. Boundary schemas and timestamp normalization are
foundations; the interface validates external values once before forwarding to
core assembly and domain policy.

## Testing Plan

- Focused execution-grant tests in every composed product.
- Poly check, kondo, inferred-stratum lint, smoke tests, and Graal compatibility.
- Full stable-derived test plan before merge.

## Standards Review

- Function design: named parent and child decisions replace nested control flow.
- Data design: one violation constructor replaces repeated map literals.
- Validation boundaries: schemas are applied in `interface.clj`, never core.
- Test design: root and child request factories replace repeated grant maps.
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
- `docs/pull-requests/2026-08-06-codex-execution-grant-breach-standards.md`
