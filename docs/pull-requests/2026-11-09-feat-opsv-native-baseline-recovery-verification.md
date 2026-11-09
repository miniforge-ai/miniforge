<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): verify native original-baseline recovery

## Overview

API surface class 1: read-only, trusted-process EDN verification of the retained
original baseline against fresh native observations, with acknowledged evidence.

## Motivation

N7 §5.5 and §6.3 require original configuration and runtime invariants to be
verified after recovery. A successful restoration patch is not that proof.

## Changes in Detail

Re-read the independently approved recovery plan and
original comparison receipt. Acquire native configuration and replica membership
through the existing bounded preparation boundary. Compare in rollback mode,
preserve raw observations and the comparison outcome, and acknowledge protected
verification-report publication. Reuse target admission with restoration.
Returned reports use the same protected content as the published artifact.

Recovery population bounds permit replacement replicas and churn. Separately
require the Deployment's desired replica count to equal the retained original;
a within-bounds candidate count is not successful restoration.

This verifies only baseline restoration, not load termination, owned-resource
cleanup, candidate SLOs, or authority to perform any recovery effect.

## Testing Plan

Real retained plan, original, native projection and evidence producers with
simulated HTTP. Test restored original, candidate substitution, unrelated drift,
replica replacement/bounds, target mismatch, missing lineage and failed evidence
publication. Run lint, JVM/product/package compatibility and fault checks serially.

- Strict lint has zero warnings/errors; all checked namespaces have at most three inferred strata.
- Both consuming JVM products, Polylith structure, task classpaths and a fresh CLI package pass.
- Source and isolated package each pass 29 recovery/restoration tests / 1,042 assertions,
  plus the prerequisite runtime/PR group at 41 tests / 546 assertions.
- Curated integration passes 345 tests / 7,843 assertions; native compatibility
  passes 308 tests / 1,903 assertions across 42 suites.
- All 27 relevant faults trigger assertion failures with zero errors.
  Six new faults cover target binding, original desired count, rollback invariants,
  protected retention, publication acknowledgement and current independent plan admission.
- With the signed interruption-cleanup prerequisite, source and rebuilt isolated
  package again pass recovery 29/1,042 and runtime/PR 42/601, plus thirteen fault checks.
  Both JVM products, structure, strict lint and task classpaths pass again.

## Deployment Plan

No live calls, mutation, execution activation, grant issuance or recovery action.

## Related Issues/PRs

Base: main after #2490 before publication. Baseline verification remains independent
of forward execution, while validation includes the governed-start prerequisite.

## Checklist

- [x] Implement native retained-baseline verification.
- [x] Source, JVM, package and fault validation.
- [x] Owner adversarial and full applicable standards review.
- [ ] Signed commits, current-head full review, CI and owner merge.
