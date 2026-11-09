<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): execute owned-load starts through current runtime governance

## Overview

Compose exact native gate release with runtime-owned current context, actual
N4 admission, N8 fencing, independent root authority and durable N3/N6 audit.
API surface class 1: trusted in-process EDN, not a serialized execution permit.

## Motivation

N7 §5.5 requires current authorization at actual provider dispatch. A native
provider or a plan approval alone does not supply that runtime integration.

## Changes in Detail

Admit the closed runtime and retained recovery input, then prepare exact current intent.
Issue and register a matching forward grant.
Audit the durable proposal and commit with an independently retained issued pin.
Recheck live context, N4, N8 and current scoped authority immediately before the
one native patch. Retain failed or uncertain outcomes without claiming traffic
or recovery. Share effect registration and failure cleanup with existing PRs.

## Testing Plan

Use actual native ownership, retained recovery, N4 decisions, root grants,
durable transactions, run-control fences and audit publication. Simulate HTTP
only. Test disabled defaults, late gate denial, stop and revocation, replay,
uncertain responses and pre/post-mutation publication failures.

- Strict lint has zero warnings/errors; all checked namespaces have at most three inferred strata.
- Both consuming JVM products, Polylith structure, task classpaths and a fresh CLI package pass.
- The broad source and isolated-package run passes lifecycle 149/4,883,
  audit/PR compatibility 39/531 and runtime/PR compatibility 41/546.
- Curated integration passes 338 tests / 7,573 assertions; native compatibility
  passes 308 tests / 1,903 assertions. All 56 injected faults fail assertions with zero errors.
- Owner review then decomposed preparation, presence admission and retained-result
  selection. Review found an interruption cleanup gap after registration.
  Final fixes touch five runtime source files and one failure-test namespace
  relative to the broad run. Other production and test sources remain byte-identical.
  Final source and freshly rebuilt isolated package each pass runtime/PR 42/601
  and all seven runtime fault checks. Both JVM products and structural/lint checks were repeated.
  The new regression preserves the exact interruption, thread flag and independent
  cleanup failure, while verifying revocation of the actual stored grant.

## Deployment Plan

No automatic activation and no live experiments. Only a trusted host may supply
the current-context reader and run-control handles. Serialized pack or agent
input cannot provide runtime authority.

## Related Issues/PRs

Base: main after #2489. Native effect audit is a prerequisite. Recovery orchestration and
full ramp/verification integration remain subsequent obligations.

## Checklist

- [x] Implement current governed native-start runtime composition.
- [x] Source, JVM, isolated package and fault-injection validation.
- [x] Owner adversarial and full applicable standards review.
- [ ] Normal signed commits, full current-head review, CI and owner merge.
