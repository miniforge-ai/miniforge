<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: enforce provider-backed merge readiness

## Summary

Consumes GitHub review-thread readback in merge readiness and decomposes the
merge application flow before N7 authority enforcement is added.

## Changes

- Replace the unresolved-thread placeholder with complete provider readback.
- Reject unavailable thread state instead of treating it as clear.
- Reject unavailable branch state without entering conflict repair or rebase.
- Separate readiness checks from blocking-reason classification.
- Extract transaction settlement, conflict resolution, and stale-branch repair
  from the provider namespace into focused orchestration functions.
- Preserve observed-merge SHA handling, auto-merge pending behavior, event
  publication, and terminal conflict anomalies.
- Centralize repeated readiness fixtures in the merge tests.

## Standards review

- Every changed namespace has at most three strata.
- Settlement `cond` arms delegate to one named outcome function each.
- Rebase and conflict paths use result pipelines without nested conditional
  ladders.
- Capability maps remain declarative injection boundaries; repeated provider
  response and test-fixture maps are centralized.
- PR budget: 593 / 600 reportable lines; every commit is at most 200.
- Kondo, Polylith, stratum lint, and the changed-code standards baseline are
  clean.

## Verification

- PR-lifecycle component passes in all three project compositions.
- Merge behavior: 20 tests / 56 assertions.
- GitHub provider behavior: 16 tests / 48 assertions.
- Pre-commit smoke: 339 tests / 1,285 assertions.
- GraalVM/Babashka compatibility: 8 tests / 606 assertions.
- Standards scan matches the repository baseline: 73 findings, including six
  manual-review findings; no new violations.

## Follow-up

The next N7 slice binds merge execution to an issued grant and a gate
DecisionEnvelope, persisting both before the GitHub effect is attempted.
