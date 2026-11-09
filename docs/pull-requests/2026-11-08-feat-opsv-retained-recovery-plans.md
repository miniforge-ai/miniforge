<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): retain protected recovery-plan admission evidence

## Layer

Application evidence publication and its uncached protected-storage boundary.

## Depends on

PR #2467 merged as `3857cd09e9c5c91b218605088c098fc79377613d` before validation.

## Overview

Retain actual independent N4 plan admission in a protected, immutable, acknowledged artifact.
Use the existing `:opsv-verification-report` type with an explicit recovery-plan-admission role and per-criterion results.
This report is not candidate SLO success, verified recovery, or execution authority.
API surface class 1: trusted in-process Clojure/EDN.

## Motivation

N7 §5.5 and N10 §1.5 require approved recovery intent to be retained before the first trial effect.
A caller-provided success flag, publication acknowledgement alone, or stale approval cannot prove this requirement.

## Changes in Detail

Invoke actual admission, build its report through the shared protected material producer, then publish and acknowledge
it through the existing confirmation boundary.
Return a retained reference only after confirmation.
Retain denials as failure evidence, never as approved plans.
Refuse protection that changes the exact recovery plan or its hash.
Require all protected envelope pins to equal the actual admission pins before publication.
If privacy protection alters a valid policy revision, refuse retention instead of acknowledging an unusable reference.

Read the artifact uncached and require current acknowledgement, producer binding, protection metadata, consistent
approval criteria and the exact plan hash.
Re-run independent admission and require the same policy revision and rule binding.
Journal progress alone does not invalidate retained approval.
Return plan intent and its reference, not an old decision or a grant for an effect.

No new artifact or event type, provider call, experiment activation, or effect authority is introduced.

## Testing Plan

Use actual admission, original/metric publication, protected report publication and assembly acknowledgement.
Cover withdrawn references, changed runtime approval and policy, retained denials, role/type substitution and
unprotected legacy records.
Publish genuinely protected but semantically wrong plans and criteria to prove fresh binding checks matter.
Check publication and acknowledgement failures after real fixture setup, plus unavailable, fatal and interrupted reads.
Run strict lint, genuine inferred strata, consuming products, curated integration, fresh isolated packages and targeted
fault checks.

Source and a freshly built isolated package pass 33 tests and 513 assertions.
The isolated package uses the JAR and test directories only, without production-source overlays.
Both consuming JVM products and Polylith structure checks pass.
Curated integration passes 190 tests and 2,373 assertions; native compatibility passes 308 tests and 1,903 assertions.
Strict lint has zero warnings or errors; all eight genuinely inferred namespaces stay within three strata.
Each of seven injected faults causes assertion failures with zero errors.
The faults cover current approval, acknowledgement, exact plan, policy revision, criteria consistency, producer binding
and pin preservation.
Actual file withdrawal proves the plan reader does not substitute cached success.
Interruption tests capture the flag before test reporting and verify the original exception propagates.
The review regression uses actual admission and protection with a valid numeric policy revision.
It proves protection changes the pin while preserving the report schema and plan/hash.
The publication guard refuses before the real publisher is called; read-time journal progress remains allowed.

## Standards Gap Analysis

Pinned standards revision: `72f451d3d7e0ad82079610bec61be0399a2b7117`.
The owner traced the actual final diff and applicable pinned rules after formatting and validation.
Reuse protected producer, material projection, immutable publication, confirmation and acknowledgement predicates.
Closed report schemas and explicit role tags prevent confusing plan admission with candidate verification or execution permission.
Projection fields, role metadata and policy bindings have one schema-owned definition.
Publication and uncached reading remain separate boundaries over the shared pure material model.
Current policy is re-evaluated; an old envelope is never returned as effect authority.
No additional standards gap was found in the scoped final pass.

## Deployment Plan

No default activation or live cluster work.
Provider capability checks, exact current effect authority and verified recovery remain separate execution obligations.

## Related Issues/PRs

N7.EX.6; N10 §1.5; N6 §3.1.1; #2440; #2461; #2467.

## Checklist

- [x] Admission dependency merged
- [x] Actual-diff adversarial and applicable standards review
- [x] Affected-product, integration, isolated-package and fault checks
- [x] Normal signed commits and hooks
- [ ] Current-head full Copilot review, resolved comments, CI and clean merge
