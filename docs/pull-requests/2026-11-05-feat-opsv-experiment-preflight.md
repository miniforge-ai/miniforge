<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Evaluate temporary experiment preflight with real N4 gates

## Overview

Application-layer preflight decisions, based on main and the approved N7/N10 amendment #2381.
The exact issuer extension is #2407; runtime composition follows this preflight layer.

## Motivation

Temporary staging trials need their own default-off runtime opt-in and N4 decision.
They cannot require the candidate SLO result they exist to measure.
Final PR and permanent-apply gates must remain unchanged.

## Changes in Detail

Reuse the existing N4 gate engine and shared decision-envelope construction.
Separate temporary-experiment checks from final-actuation permission.
Keep trusted runtime policy distinct from experiment-pack and agent input.
No native provider execution, grant issuance, or live activation is part of this change.

The public `evaluate-experiment-preflight` boundary accepts the workflow context,
canonical native preparation request and correlated original-baseline receipt.
Runtime `:opsv/experiment-execution` settings default off in the component resource.
Enabling requires a closed staging declaration with an independent workflow/Deployment target.
The environment must match the pack; the namespace and Deployment must be pack-declared.
Existing correlation checks bind candidate, pack, attempt, target and comparison pin.
Runtime input must carry that same pack, and the observed baseline must equal the original pin.

Instrumentation, environment, blast-radius, abort and evidence gates run through N4.
Final actuation permission and candidate SLO success are not trial prerequisites.
A permanent production allowlist cannot enable temporary production experiments.
Experiment-specific output keys prevent confusion with the final-actuation envelope.
This decision proves no retention, ownership, N8 fencing or provider authorization.
Runtime composition must satisfy those obligations and reevaluate at every commit.

Extract the existing final-governance schema and evaluation without changing its contract.
One shared refusal factory and envelope producer replace duplicated gate-result construction.
The existing final-actuation regression suite is unchanged.

## Testing Plan

Exercise the real N4 gate registry and envelope producer, without mocking their collaboration.
Cover disabled defaults, malformed policy, allowed staging, and individual safety-gate refusals.
Regress final actuation and run strict lint, inferred strata, package and composition checks.
Keep local JVM jobs serialized and all normal signed, review, CI and owner merge gates.

Validation passes:

- Strict entire-brick and project lint: zero warnings or errors.
- All ten changed namespaces pass true inference with at most three strata.
- Workspace composition and phase-opsv tests pass in all four product contexts.
- Existing governance and new preflight regressions: 17 tests, 107 assertions.
- All 23 scoped OPSV/workflow/N6 JVM integration suites: 105 tests, 1,287 assertions.
- All task entrypoints load, and the distributable CLI builds from these sources.
- Isolated Babashka artifact, test paths only: 17 tests, 107 assertions.
- Packaged native compatibility: 308 tests, 1,903 assertions.
- Seven deliberate authority faults are detected by assertion failures, all with zero errors.

Only the HTTP transport is simulated in the primary integration tests.
Native capture, preparation, correlation, real gates and envelope construction collaborate.
The initial package harness wrongly loaded the Babashka-only artifact in a plain JVM.
Correct the harness to use its supported runtime; JVM product/integration checks pass separately.
No source overlay, runtime weakening, live effects or layer exceptions are used.

## Deployment Plan

Default off; no live experiments or cluster changes.
Runtime current-authority/N8 composition and native execution remain subsequent work.

## Related Issues/PRs

N7 completion; #2381, #2392, #2407.

## Checklist

- [x] Implement the scoped preflight producer and adversarial standards pass.
- [x] Validate real gate composition, defaults and final-actuation compatibility.
- [ ] Complete all review, CI and owner merge gates.
