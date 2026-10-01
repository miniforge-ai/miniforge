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
Artifact Transit extensions preserve Instant values, including nanosecond precision.
The codec, record selection, outcome projection, recovery and exception boundaries
have separate responsibilities. No provider or schema dependency is duplicated.

## Changes in detail

- Retain actual failed, uncertain and successful transactions on failed paths.
- Publish transaction and failure artifacts alongside the actuation record.
- Confirm terminal disposition events before finalizing a failed bundle.
- Record an unsuccessful N6 outcome for failures observed before finalization.
  A confirmed or matched reconciled PR remains confirmed; an uncertain POST
  does not invent a provider reference.
- Recover from a saved failed checkpoint using only audit, artifact and bundle
  operations. Preserve the failed phase result and checkpoint the updated assembly.
- Retry immutable publication once finalization has completed. Never reopen the
  assembly or invoke actuation during evidence recovery.
- If publication fails after finalization, preserve the already sealed bundle's
  original outcome. Retain the later publication failure in the failed phase
  checkpoint; recovery never rewrites that immutable bundle or reports phase success.

## Testing plan

The eight terminal tests cover uncertain POST, confirmed and reconciled success,
and transient and persistent audit failure. They cover artifact, event and bundle
publication failures, checkpoint recovery, repeated recovery, and invalid recovery inputs.
Artifact tests also prove exact Instant type and nanosecond round trips.
Both phase-consuming projects pass their complete OPSV suites. The rebuilt CLI passes
the checkpoint, terminal and finalization suites. A disk round trip uses
the same public timestamp-normalization function as shared checkpoints.
It removes runtime stores and phase state, then restores exact outcomes without provider replay.
The coercion component is a test-only dependency. Runtime ports are supplied anew
by the trusted host; snapshots cannot create provider authority.

A separate shared-runner integration test passes 1 test / 10 assertions. It runs
all seven phases, confirms simulated provider success, and forces terminal audit failure.
It loads the real workflow checkpoint and recovers evidence with the provider runtime
removed. The exact transaction and failed outcome survive; repeated recovery leaves
the provider call count at one GET and one POST. CI executes this test explicitly.
Only test-owned preparation binds the simulated diff; production host preparation
remains a separate slice.

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
