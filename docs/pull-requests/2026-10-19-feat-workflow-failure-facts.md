<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Publish authoritative workflow failure facts

## Purpose and layer

Workflow producer integration for N3 terminal events: preserve the actual failure
reason and classify it with the existing runtime taxonomy. Execution errors take
precedence; native phase anomalies supply facts when no execution error exists.
Missing diagnostic facts remain the explicit canonical unknown class. Preserve
the original error records and known failure phase.

## Dependencies and scope

Branched from main after #2054. Independent of phase-completion-facts and terminal
done-phase facts. This does not migrate the default host or make workflow-level
publication refusals fatal; those are separate slices. No storage migration;
normal CLI deployment.

## Verification plan

Cover canonical/legacy anomalies, typed exception provenance, precedence, native
phase failures and missing facts. Exercise actual enter-error normalization through
the workflow publisher and current journal reopen, retain legacy behavior, and
verify all product consumers plus a rebuilt isolated CLI. Normal budgets, signed
hooks, adversarial standards pass, exact-head review and full CI remain required.

## Local verification and adversarial review

- All three workflow product consumers: passing, serially (1m28s).
- Rebuilt CLI: 39,186,356 bytes. Isolated package, no production source overlay:
  42 tests, 174 assertions, passing.
- Kondo: no warnings/errors. All changed namespaces match inferred strata.
- Reviewed anomaly and exception precedence, failure location ownership, retained
  error records, native phase fallback and explicit unknown classification.
  The existing classifier owns taxonomy rules; no duplicate classification table
  or fabricated phase is introduced. Test support is shared and stays within
  three strata. Actual enter normalization and journal persistence are exercised;
  this is not a default-host or full workflow lifecycle acceptance claim.
