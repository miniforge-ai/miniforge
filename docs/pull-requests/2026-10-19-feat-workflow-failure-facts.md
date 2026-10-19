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

- All three workflow product consumers: passing, serially (1m57s).
- Rebuilt CLI: 39,187,322 bytes. Isolated package, no production source overlay:
  50 tests, 218 assertions, passing after all review corrections.
- Kondo: no warnings/errors. All changed namespaces match inferred strata.
- Reviewed anomaly and exception precedence, failure location ownership, retained
  error records, native phase fallback and explicit unknown classification.
  The existing classifier owns taxonomy rules; no duplicate classification table
  or fabricated phase is introduced. Test support is shared and stays within
  three strata. Actual enter normalization and journal persistence are exercised;
  this is not a default-host or full workflow lifecycle acceptance claim.

## Review correction

Canonical exception anomalies store their exception class inside anomaly data.
The projection now reads that canonical provenance as well as the legacy fields.
A regression constructs an actual canonical SocketTimeoutException anomaly with
a message that does not imply timeout, and verifies timeout classification while
preserving the original anomaly and error record.

A subsequent review identified native string-valued errors, used by deployment
configuration failures. The message projection now preserves those diagnostics
directly. Regression coverage includes result-level and phase-level strings;
map-shaped diagnostics and execution-error precedence remain unchanged.

When both phase error fields exist, the phase-level anomaly now precedes its inner
result diagnostic, matching review's typed backend-timeout producer. Execution
errors still take precedence over both. A pre-fix CLI regression reproduced lost
anomaly code and provenance; the rebuilt CLI preserves both through actual
terminal publication and journal reopen. Shared verification support avoids
duplicating the publication assertions.

Matching native summaries now retain the inner response's exception data; typed
phase anomalies still win outright. The chosen diagnostic is preserved in event
details. Gate-only failures fall back to the latest matching phase response-chain
entry, retaining its actual gate anomaly and policy classification. Regressions
reproduced four failures against the previous CLI, then passed after rebuilding.
Unrelated summaries, superseded gate entries, other-phase entries and later
execution errors cannot borrow those diagnostics. Source selection is a separate
small namespace from event projection; the classifier remains the single taxonomy.
