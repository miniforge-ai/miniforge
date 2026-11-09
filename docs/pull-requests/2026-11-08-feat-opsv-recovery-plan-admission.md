<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): independently admit exact bounded recovery plans

## Layer

Application admission and its N4 registry adapter, with shared domain target checks.

## Depends on

Depends on merged PR #2461 for bounded native recovery-plan construction from retained originals.

## Overview

Reconstruct recovery intent from the acknowledged immutable original.
Require independent runtime-owned approval of its exact hash and an explicit staging declaration.
Run the existing safety gates and the separate recovery-plan approval gate through the real N4 engine.
Return its actual pinned DecisionEnvelope, not a caller-provided verdict.
Name the output `:recovery/plan-decision-envelope` to distinguish plan admission from authorization for a recovery effect.
API surface class 1: trusted in-process Clojure/EDN.

## Motivation

N7 §5.5 and N10 §1.5 distinguish plan construction, independent approval and recovery execution authority.
Forward opt-in, final actuation configuration and candidate SLO success cannot replace plan approval.

## Changes in Detail

Use separate `:opsv/recovery-plan-admission` runtime options.
Absent approval denies admission; malformed settings return an anomaly.
Bind approved hashes to exact original identity, attempt, load payload and recovery limits through the existing plan hash.
Share the existing target predicate rather than duplicate its declaration, pack membership and baseline checks.
Keep production allowlisting unable to enable staging experiments.
Denials carry machine-readable remediation actions and localized human guidance.
Retain intrinsic target refusals separately from registered gate checks, without changing the gate engine's check summary.

No experiment activation, execution grant, provider call or successful candidate-verification claim is introduced.
Protected retention and current authority at each recovery effect remain separate obligations.
Plan admission alone does not prove provider capabilities or recovery postconditions.

## Testing Plan

Use actual retained-original publication and acknowledgement, the native plan factory and the real N4 gate engine.
Cover pinned allowing decisions, default denial, independent approval, malformed settings, changed load hashes and
withdrawn acknowledgement.
Check every applicable safety gate, strict lint, genuine inferred strata, consuming products and fresh isolated packages.
Inject targeted faults to verify assertion failures without test errors.

All eight genuinely inferred changed namespaces passed at three or fewer strata.
Strict lint passed with zero warnings/errors; Polylith check and both consuming JVM products passed.
Focused source and fresh isolated-package suites passed 24 tests/300 assertions.
Curated integration passed 175 tests/2,142 assertions; native compatibility passed 308 tests/1,903 assertions.
All five injected faults caused assertion failures with zero errors.
The isolated package used merged dependencies and no production-source overlay.

## Standards Gap Analysis

Pinned standards revision: `72f451d3d7e0ad82079610bec61be0399a2b7117`.
Applicable foundations, Clojure, Polylith, API-surface, test, workflow, documentation and copyright rules were read in full.
The owner reviewed the actual whole diff after formatting and final validation.
Share the target predicate, centralize configuration schemas and localize diagnostic copy.
Boundary registration keeps the N4 registry adapter out of the admission model's dependency list.
The owner pass clarified plan-only decision names and retained intrinsic target refusals with canonical detailed remediation.
Fixture evidence uses actual publication and acknowledgement, not fabricated snapshot references.

## Deployment Plan

No default activation or live cluster work.
Operator-owned settings approve only the exact declared recovery plan, never execution.

## Related Issues/PRs

N7.EX.6; N10 §1.5; #2440; #2441.

## Checklist

- [x] Constructor dependency merged
- [x] Actual-diff adversarial and applicable standards review
- [x] Affected-product, integration, isolated-package and fault checks
- [ ] Normal signed commits and hooks
- [ ] Current-head full Copilot review, resolved comments, CI and clean merge
