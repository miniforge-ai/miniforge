<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: report pre-phase snapshot write failures through the registered N3 event

## Overview

Close the existing N2/N3 failure-reporting obligation without amending contracts.
Base: main. Dependencies #2299 and #2304 are merged and integrated.

## Motivation

The runner logs checkpoint failures before any phase result exists but emits no
event. N3 §3.21 already registers `workflow/machine-snapshot-write-failed` for
this case. Observers otherwise miss that the run cannot be resumed.

## Changes in Detail

- Project phase and pre-phase failures into their respective registered events.
- Preserve the warning, canonical failure classification and continued execution.
- Keep event projection separate from reporting, within three actual strata.

## Testing Plan

Reproduce missing pre-phase failure publication; verify current-journal facts,
legacy/no-stream compatibility, classification, returned anomaly and fatal errors.
Then run affected workflow suites, normal hooks, standards and packaged acceptance.

The real pre-phase regression failed six of ten assertions before the change,
with no errors. After the change, all ten assertions pass. It initializes the
compiled workflow's authoritative pending state and uses an unwritable temporary
checkpoint root, rather than constructing an artificial failure event.

Expanded source acceptance passed eight tests and 41 assertions, including
the existing phase-checkpoint cases. Missing and observer-only streams warn
without publication; legacy streams retain their event contract. Resource
classification follows the original exception, not its generic anomaly wrapper.
Refused or nonfatal failed publication preserves the original write anomaly;
fatal publication errors propagate.

With current main and the verified #2304 dependency integrated, all three
workflow-consuming product suites passed (2m51s). A fresh CLI build produced a
6,880,674-byte stripped jar. Isolated packaged acceptance passed 35 tests and
146 assertions, including failure, location-retention and start/terminal paths.
No production-source overlays were used.

Actual-main reconciliation preserved the tested snapshot producer and adopted
the exact reviewed parent versions for retention files. The producer sources
had no incoming changes beyond the already integrated parent. All three
workflow-consuming product suites passed again (3m03s); a fresh build and
isolated packaged acceptance passed 36 tests with 160 assertions.
The adversarial pass also moved message-field computation above map assembly
and kept fixture maps legible without changing failure semantics.
Final focused acceptance passed 11 tests and 50 assertions. A fresh stripped
CLI jar contains the final producer sources; isolated acceptance again passes
36 tests and 160 assertions without production-source overlays.

## Deployment Plan

No journal migration or new schema version. Failure events remain payload 1.0.0.
No changes to storage, permission or recovery authorization policies.

## Related Issues/PRs

N3 §3.21 and the N2 checkpoint/resume delta; follows the checkpoint-location chain.
This change is not an N7 completion claim.

## Checklist

- [x] Regression and affected-project tests
- [x] Adversarial standards review, lint and inferred layers
- [ ] Normal hooks, signing, fresh Copilot review and all CI gates
