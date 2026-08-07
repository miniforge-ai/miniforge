<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: harden execution-grant breach history

## Overview

Bring breach persistence, eligibility, and for-cause revocation into compliance
with exception, component, function, test-data, and stratified-design standards
before production grant issuance is enabled.

## Motivation

The existing breach module threw on invalid input and filesystem conflicts,
treated unreadable history as a clean principal, and combined record encoding,
filesystem storage, ceiling policy, eligibility, and revocation in one deep
call chain. Its tests also repeated full breach and observation maps.

Those defects are authority defects: a corrupt audit store must not make a
principal eligible, and a failed evidence write must not masquerade as a
complete for-cause revocation.

## Layer

Execution-grant domain policy and internal infrastructure hardening.

## Changes in Detail

- Return anomalies for invalid records, duplicate ids, and filesystem failures.
- Fail eligibility closed when breach history is inaccessible or corrupt.
- Preserve append-only evidence with exclusive hard-link publication.
- Record breach evidence before revoking and leave the grant live if recording
  fails.
- Separate breach API/storage, ceiling policy, eligibility, and revocation.
- Centralize breach, observation, and grant factories in tests.
- Move emitted failure text into the component message catalog.

## Architecture

The breach namespace is a thin component-internal API over the append-only
store. The store contains only filesystem discovery, individual record I/O, and
the two public storage operations. Ceiling derivation is separate from the
boolean issuance predicate, while for-cause revocation owns its two-effect
ordering explicitly. Every implementation namespace has at most three inferred
layers.

## Failure Semantics

- Missing history directory is the normal empty-history case.
- Existing non-directory, unreadable directory, malformed EDN, and invalid
  stored records return `:fault` anomalies.
- Reusing a breach id returns `:conflict` and preserves the original record.
- Invalid caller input returns `:invalid-input` without touching storage.
- Eligibility returns false for every breach-store anomaly.

## Testing Plan

- Focused execution-grant tests in every composed product.
- Full five-project stable-derived test plan.
- Poly check, kondo, inferred-stratum lint, standards scan, smoke tests, Graal
  compatibility, and PR budget.

## Standards Review

- Exceptions as data: both execution-grant scanner findings are removed.
- Component design: filesystem concerns are isolated behind the breach API.
- Function design: policy functions no longer construct repeated maps inline.
- Data design: one fixture factory per canonical breach/test input shape.
- Stratified design: the former six- and five-layer namespaces are decomposed;
  staged lint validates the resulting dependency depth.
- Failure design: no read or write failure widens authority.

## Checklist

- [x] Commit budgets pass without overrides.
- [x] Focused execution-grant tests pass in all four composed products.
- [x] Poly, kondo, stratum, smoke, and Graal gates pass.
- [x] Adversarial failure-path and changed-code review is clean.

## Related Work

- `work/ariadne-grant-issuance.spec.edn`
- Follow-up: delegation and attenuation standards remediation.
