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
- Keep N6 outcome false when the phase failed or stopped. A confirmed PR remains
  a confirmed PR; an uncertain POST does not invent a provider reference.
- Recover from a saved failed checkpoint using only audit, artifact and bundle
  operations. Preserve the failed phase result and checkpoint the updated assembly.
- Retry immutable publication once finalization has completed. Never reopen the
  assembly or invoke actuation during evidence recovery.

## Testing plan

The five terminal tests cover uncertain POST, confirmed success, and transient
and persistent audit failure. They cover checkpoint recovery without a runtime
store, repeated recovery, and refusal of nonterminal recovery inputs.
Artifact tests also prove exact Instant type and nanosecond round trips.
Both phase consumers pass 64 tests / 487 assertions. The rebuilt CLI passes
24 terminal/finalization/publication tests / 173 assertions.

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
- [ ] Signed commits, settled review and all CI green
