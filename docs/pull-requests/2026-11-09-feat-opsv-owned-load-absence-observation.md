<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): observe proven owned-load resource absence

## Overview

Observe whether an independently recorded experiment-owned load Pod is absent
from the exact retained runtime target. A deletion acknowledgement is not this
proof. Observation neither deletes resources nor grants recovery authority.

## Motivation

N7 §5.5 requires actual owned-resource cleanup verification on every trial exit.
Missing resources on a foreign cluster or namespace cannot establish cleanup.

## Changes in Detail

Reuse actual retained recovery approval, protected original preparation and
durable native creation ownership. Share the existing durable ownership
admission without changing the live-ownership API's refusal of absent Pods.

Check original physical cluster and namespace identities both before and after
the exact owned-Pod read, under one shared deadline. Only native HTTP 404 is
absence; JSON null, foreign UIDs and ambiguous reads remain unresolved. Preserve
explicit present, absent or unresolved outcomes. Never adopt or delete a
same-name foreign resource. Admission failures remain anomalies before HTTP.

## Testing Plan

Use actual retained plan/baseline and native creation producers; simulate HTTP
only. Cover absent versus present resources, replaced identity, wrong roots,
bounded reads, ambiguous responses and interrupted acquisition.

- Strict lint: zero warnings/errors; every checked namespace has at most three inferred strata.
- Polylith structure, both consuming JVM products and task classpaths pass.
- Source and isolated fresh package: 149 tests / 4,883 assertions each.
- Curated OPSV/workflow/artifact integration: 312 tests / 6,882 assertions.
- Native compatibility: 308 tests / 1,903 assertions across 42 suites.
- Forty-four faults each trigger assertion failures with zero errors.
  Six absence faults cover closed input, durable ownership, physical roots,
  UID checks, foreign or null observations and deadline reset. The remaining
  faults preserve restoration, forward-start and cleanup safeguards.

## Deployment Plan

Read-only and not automatically wired to run experiments. No live cluster
actions are authorized. Final recovery also requires observed load termination
and verified original-baseline runtime invariants.

## Related Issues/PRs

Base: main. Builds on merged #2483 and #2485. Native runner composition and
every-exit recovery verification remain separate obligations.

## Checklist

- [x] Implement identity-bound native absence observation.
- [x] Source, JVM, isolated package and fault-injection validation.
- [x] Owner adversarial and full applicable standards review.
- [ ] Normal signed commits, full current-head review, CI and owner merge.
