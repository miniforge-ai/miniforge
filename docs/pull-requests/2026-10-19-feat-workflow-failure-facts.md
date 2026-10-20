<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Publish authoritative workflow failure facts

## Purpose and layer

Workflow producer integration for N3 terminal events: preserve the actual failure
reason and classify it with the existing runtime taxonomy. Current-attempt execution errors take
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

- All three workflow product consumers: passing, serially (1m24s).
- Rebuilt CLI: 39,189,005 bytes. Isolated package, no production source overlay:
  34 tests, 210 assertions, covering failure publication, diagnostics, native
  handlers, checkpoint storage and context after the redirect correction.
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

Actual deployment error handlers append summary-only diagnostics after the caught
exception. The error-handler boundary now retains provenance only on newly appended
matching phase/message/data records from that same invocation. Existing causes and
historical or unrelated records remain unchanged. Exception recording accepts the
interceptor name when its config omits the phase, as native deployment producers do.
The integration regression uses all three real deployment handlers, plus verify's
real leave handler for a gate-failed summary with a successful agent result.

Each phase attempt records the existing error-history length before entering.
Terminal facts select only subsequent execution errors, leaving older diagnostics
available in the event's complete history. The attempt boundary survives durable
checkpoint/resume and is removed on explicit rewind. Legacy snapshots without the
field retain their existing selection behavior. This prevents a recovered earlier
exception from overriding a later native failure without deleting audit history.

Terminal FSM projections intentionally clear the active phase. Native failures
therefore also persist the explicit attempted phase, independently of the active
state projection. Failure publication uses that identity when no current phase or
execution-error phase is available; it does not guess from pipeline ordering.
Explicit rewind removes it. A real execution-step failure is checkpointed,
restored into its terminal FSM state, published and verified through journal
reopen. The preceding package reproduced three lost-identity/diagnostic assertions.

The checkpoint encoding also turns canonical anomaly timestamps into strings.
A small diagnostic reader decodes only that timestamp representation and validates
against the existing anomaly contract; invalid timestamps or malformed anomalies
remain rejected. Live and legacy causes remain supported. The original checkpoint
and error-history values are not mutated. Negative decoding tests accompany the
durable restore regression.

Native and restored publication regressions have an explicit project-level CI step
and the shared workflow test path in the test alias. The exact CI command passes
locally: 3 tests / 69 assertions, without custom classpath expansion.

The restore fixture now includes a backward review-to-implement transition and
existing checkpoints for both phases before implement fails natively. This caught
the checkpoint writer selecting the later review result by pipeline order.
Checkpoint and output selectors now use the persisted attempt identity after the
active phase and before their legacy ordering fallback. The old package fails the
new diagnostic assertion; the exact project regression passes 1 test / 16
assertions. All three consumers and rebuilt-package revalidation pass, as above.
