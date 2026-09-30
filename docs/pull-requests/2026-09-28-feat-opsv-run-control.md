<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Coordinate supervised OPSV run stops

## Overview

Add a host-owned stop domain for active OPSV runs. Close one shared admission
fence before invoking run cleanup, then request safe-boundary load aborts and
persist revocation of each tracked mutation grant.

## Motivation

A run-local fence alone cannot enforce a fleet stop. Iterating over run callbacks
without first closing shared admission leaves other runs able to mutate while
an earlier run is still stopping.

## Layer and dependencies

Application orchestration in `phase-opsv` composes the public mutation-fence and
durable-grant interfaces. No new component dependency is introduced.

## Changes in detail

- Create opaque factory-owned supervisor and run handles. Registration races
  with stop under the supervisor lock; duplicate or post-stop registration fails.
- Bind the run handle to its workflow, authority directory and local fence.
  Reject substituted handles before grant issuance or provider I/O.
- Recheck shared admission at the exact post-preflight provider boundary.
- Track issued authority. A grant whose registration races stop is revoked
  before the provider can receive it.
- Stop every local fence before calling abort handlers. Individual failures do
  not skip other runs; reports retain abort and revocation failures for retry.
- Separate cleanup confirmation from whether admitted effects have settled.
  Retirement retains the run until both are confirmed. No stop reopens a fence.

## Validation

Eight run-control tests and 38 assertions pass on JVM and packaged Babashka.
Tests cover preflight/registration races, successful-effect grant cleanup,
substituted runtime handles, failed aborts, failed revocation retry and in-flight
retirement. No test calls a live provider.
The phase standards scan reports no findings across 56 files. Kondo, strata and
signed commit hooks pass. The CLI build succeeds.

## Standards adversarial pass

State registration, cleanup boundaries and orchestration have separate namespaces
and at most three dependency strata. Runtime configuration remains closed and
validated. Shared failure constructors and diagnostic catalogs avoid repeated
maps and raw operator-facing strings. Revocation confirms durable readback when
a concurrent writer makes its own write result uncertain.

## Deployment and remaining integration

The host must register a run before execution and pass both returned handles
into its trusted PR runtime. The abort callback acknowledges a request, not a
completed rollback or cancelled in-flight mutation. Retry incomplete cleanup.
N8 operator/safe-mode host wiring, real adapter safe-boundary aborts, restart
recovery and durable stop-disposition publication remain follow-on work.
This change does not claim those acceptance requirements are complete.

## Checklist

- [x] Focused and packaged tests, standards, kondo, strata and hooks pass
- [ ] Final consumer rerun after added race/cleanup regressions
- [ ] Final-head review settled, all CI green and conflict-free merge
