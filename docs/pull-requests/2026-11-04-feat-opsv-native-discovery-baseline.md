<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Discover native OPSV baseline and correlated scaling drivers

## Overview

Connect complete native Kubernetes capture and canonical environment projection
to CPU/backlog discovery without executing experiments or changing clusters.

## Motivation

The native backend needs the same drift-sensitive baseline representation during
discovery and later verification. The existing snapshot discovery API remains
available with its original shape.

## Changes in Detail

Expose `discover-kubernetes-verification-signals!`, extending discovery settings
with `:discovery/declaration` and `:discovery/replica-bounds`. Admit complete
finite, metadata-free configuration before validation or reads. Compose existing
bounded native acquisition, canonical projection and Prometheus clients.

Native target identity and controller desired counts must fit the declaration
and bounds; missing runtime stops before metrics. Return original sensitive
observations and semantic views under `:discovery/kubernetes-verification`, plus
both label-bound drivers. Repeated calls reacquire changed configuration.
The projection is not population-match admission, a health/freshness verdict,
retained evidence or execution authority. Protect originals before publication.

Extract shared CPU/backlog acquisition for both discovery paths. The existing
snapshot API keeps its original configuration and result shape. Compose schemas
from existing target, count and discovery contracts; no duplicated metric policy
or native projection. New code follows inferred downward strata.

## Testing Plan

Simulated transports only. Cover target/bounds admission, canonical baseline,
originals, metric label binding, interrupted/failing reads and changed state.
All local JVM/build/hook runs use the shared slot. All PR gates remain required.

Verified at `fcb066594bc44924083f5ac3f44b3a2a460497a0`:

- Strict kondo: zero errors/warnings; inferred strata and poly check pass.
- Both phase-OPSV product contexts pass; all 19 scoped OPSV/workflow/N6
  integration namespaces pass: 82 tests, 1,040 assertions.
- Fresh CLI and isolated packaged tests: 269 tests, 1,562 assertions.
- Source suites including existing discovery regression: 17 tests, 118 assertions.
  New native suites: ten tests, 73 assertions; both unit cases below 100 ms.
- Eight new fault injections detected with zero test errors; all 14 existing
  producer-privacy and 19 native-reporting fault injections also detected.
- Committed scans: zero violations/304 component files and zero/15 project
  OPSV files. PR: 282 reportable lines; no size exception.

Fixtures use actual native capture and decoded metric producers, with simulated
transport only. Unrelated local executor lifecycle suites can mutate configured
backends and were not invoked. No tests or assertions disabled; complete
repository-wide isolated CI remains mandatory unchanged.

## Deployment Plan

Read-only API only; no backend activation, load, effects or cluster operations.

## Related Issues/PRs

N7 discovery and environment comparison; native capture #2366, projection #2370,
and preparation #2373 (not a dependency of this discovery implementation).

## Checklist

- [x] Implement and adversarially review the native discovery composition.
- [x] Verify source, package, fault and standards gates.
- [ ] Settle review, require fresh full review/CI, and owner-merge.
