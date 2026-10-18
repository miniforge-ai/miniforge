<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Preserve terminal OPSV evidence without repeating actuation

## Overview

Complete N6 evidence for failed and uncertain PR actuation. Keep the workflow
failed and preserve the actual durable transaction, even when the provider
succeeded before a later audit failure. Add evidence-only checkpoint recovery.

## Motivation

A failed phase must not discard its experiment, verified policy, grant join or
provider outcome. Retrying evidence must never retry the external mutation.

## Layer and dependencies

The OPSV application coordinates existing public artifact and evidence APIs.
It builds on merged finalization #1952 and acceptance #2042. Pure terminal
classification, publication sequencing, checkpoint recovery and provider-outcome
projection have separate responsibilities. No provider or schema dependency is duplicated.

## Changes in detail

- Retain actual failed, uncertain and successful transactions on failed paths.
- Publish transaction and failure artifacts alongside the actuation record.
- Confirm terminal disposition events before finalizing a failed bundle.
- Record an unsuccessful N6 outcome for failures observed before finalization.
  Preserve the canonical workflow tier while projecting the terminal outcome.
  A confirmed or matched reconciled PR remains confirmed; an uncertain POST
  does not invent a provider reference.
- Recover from a saved failed checkpoint using only audit, artifact and bundle
  operations. Preserve the failed phase result and checkpoint the updated assembly.
- Retry immutable publication once finalization has completed. Never reopen the
  assembly or invoke actuation during evidence recovery.
- If publication fails after finalization, preserve the already sealed bundle's
  original outcome. Retain the later publication failure in the failed phase
  checkpoint; recovery never rewrites that immutable bundle or reports phase success.
- Retain the original actuation event batch before terminal publication, including
  timestamps and legacy sequence reservations. Only host-owned recovery checkpoints
  can supply a retained batch. Partial journal retries reuse acknowledged events.
- Block ordinary transforms while terminal evidence recovery is pending, even if
  the caller removes evidence configuration. Preflight rejection without retained
  actuation does not create a terminal snapshot.
- Restore the captured workflow tier after disk restart and reject malformed
  recovery options before modifying checkpoint state.
- Create terminal snapshots only for evidence-opted-in runs. Legacy callers can
  correct rejected runtime options; existing snapshots still prohibit replay even
  when the caller removes evidence opt-in.
- Reuse acknowledged legacy dispositions before allocating a sequence number.
  Retain unacknowledged drafts across checkpoint recovery, including their reserved
  positions. Current journals continue to use indexed acknowledgment without a
  history query.

## Testing plan

The terminal regressions cover uncertain POST, confirmed and reconciled success,
and transient and persistent audit failure. They cover artifact, event and bundle
publication failures, checkpoint recovery, repeated recovery, and invalid recovery inputs.
Both phase-consuming projects pass their complete OPSV suites. A disk round trip uses
the same public timestamp-normalization function as shared checkpoints.
It removes runtime stores and phase state, then restores exact outcomes without provider replay.
Project integration tests use the existing project test alias and shared component
fixtures, without adding a component dependency. Runtime ports are supplied anew
by the trusted host; snapshots cannot create provider authority.

Additional acceptance coverage is preserved for an immediate follow-up PR.
It covers current-journal partial publication with disk reopen, projection failure,
preflight correction, untrusted event batches, and shared-runner checkpoint recovery. These
tests are verified with the full runtime before splitting to respect the PR budget.
The follow-up also carries confirmed, reconciled, unknown and transient-audit outcome
acceptance cases; runtime regressions retain persistent-audit and disk-recovery coverage.
Production host preparation remains a separate implementation slice.

## Standards adversarial pass

Share fixture constructors instead of repeating evidence-base maps. Keep pure
outcome projection separate from audit and publication effects. Reuse the existing
localized failure boundary. All touched namespaces remain within three strata.
Scoped scans, kondo and both consumer suites must pass before submission.

## Deployment plan

Opt-in host evidence configuration remains required. Persist the context returned
by recovery. Persistent audit or storage failure leaves evidence incomplete and
retains the real effect outcome; it does not grant permission to replay a POST.
This does not supply the host's final production wiring or a Kubernetes adapter.

## Related work

Follows the phase-artifact and N6 finalization slices. Requires durable artifact
publication and the governed PR runtime/audit chain.

## Checklist

- [x] Focused terminal regressions pass
- [x] Final packaged and all-consumer verification
- [x] Signed commits and repository hooks
- [ ] Settled review and all CI green
