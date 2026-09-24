<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: report only implemented OPSV actuation

## Overview

Keep the recommendation-only OPSV executor from reporting PR creation or apply
when it performs neither. This is an application-layer safety fix, not completion
of the governed-actuation work item.

## Motivation

Caller-supplied capability flags could select an external effective mode while
the resulting record contained no governed effects or provider references.
N7 sections 1.4 and 5.4 require execution authority, not claimed permission.

## Changes in Detail

- Retain requested intent, but limit the current executor to recommendations or
  safe-mode disposition (`:none`).
- Ignore caller capability claims; this executor has no grant-backed effect path.
- Derive closed gates from the canonical vocabulary instead of repeating maps.
- Decompose record assembly and preserve upstream anomalies.
- Test every requested mode with forged capability flags and safe mode.

## Testing Plan

- OPSV and phase-OPSV: 34 tests, 256 assertions in each consuming project.
- OPSV lifecycle integration: 4 tests, 42 assertions, including persisted evidence.
- Pre-commit: Polylith, kondo (zero warnings/errors), strata and formatting pass;
  347 smoke tests / 1,310 assertions and 8 compatibility tests / 667 assertions pass.
- Adversarial review traced forged flags, every requested mode, safe mode,
  malformed intent, missing verification and upstream anomaly propagation.
- Review follow-up pins missing/empty/nil verification in normal and safe modes
  to the existing typed schema rejection. No redundant interior validation added.
- Repository scan also found pre-existing findings outside this change. Those
  need separate review; this PR does not claim repository-wide standards closure.

## Deployment Plan

Merge after passing CI and settled review. No migration is required. Consumers
will now see recommendation-only results where no external effect occurred.

## Related Issues/PRs

`work/n07-opsv-governed-actuation.spec.edn` remains open. Follow-up PRs add
grant-backed PR creation, apply/postconditions/rollback, and emergency-stop
integration before CLI/TUI/drift and agent-budget work.

## Checklist

- [x] Regression and lifecycle tests pass.
- [x] Scoped standards audit and pre-commit checks pass.
- [ ] Review comments are addressed and resolved; CI passes.
