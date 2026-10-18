<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# test: Verify OPSV restart and partial-publication recovery

## Overview

Restore the broader acceptance coverage preserved when finalization #1952 was
split to respect the review-size limit. This PR changes tests and their explicit
CI/project-runner registration only; it does not change production behavior.

## Layer and dependencies

Integration acceptance. Based on `main` at `03b8b5571`, with #1952 merged.
The three suites are restored from the verified checkpoint `75c67bf4f`, retained
under `work/opsv-finalization-full-20261018`.

## Changes in detail

- Reopen a current journal after success, bundle-publication failure, and partial
  event publication. Confirm immutable evidence, unchanged acknowledged events,
  contiguous positions, and refusal to execute a finalized run again.
- Recover legacy artifact/event publication after an intervening workflow event.
  Confirm original sequence reservations, unchanged evidence intent, one actuation
  call, and refusal of corrupt or authority-free recovery inputs.
- Fail closed when the post-actuation snapshot cannot be encoded, retaining the
  actual outcome and blocking ordinary replay.
- Run all seven OPSV phases through the shared workflow runner. Load the real
  checkpoint and recover immutable evidence with baseline and fresh measurement
  artifacts, including after bundle publication throws.

## Validation

The preserved full tree passed 34 tests / 372 assertions against the isolated
packaged CLI before the split, without production-source overlays. The runtime
foundation additionally passed both consumer suites and all GitHub CI checks.
The restored suites and targeted unit regressions pass through the project runner
on merged main: 34 tests / 372 assertions. The same 34/372 pass outside the repo
against the verified CLI with test-only overlays. Production sources are identical
to that built artifact, so no redundant rebuild was needed. Kondo reports zero
errors/warnings; explicit strata and Polylith checks pass. The pinned standards
scan reports zero violations across 4,438 files. Whole PR: 233 reportable lines.

## Standards adversarial pass

Reuse the existing evidence-base, lifecycle, checkpoint-root and isolation
fixtures. Filesystem-heavy tests remain in the project integration suite; test
resources are exposed through the test alias, not production paths. Keep each
namespace within three dependency strata. Assertions check real stored evidence,
event identity, sequence reservations and provider-call counts rather than just
successful return values. No repeated production maps or new authority shapes.

## Deployment and limitations

No runtime changes or configuration changes. The tests use simulated adapters
and do not claim real-provider/load conformance. Failed/uncertain actuation
terminal evidence remains the separate #1948 workstream.

## Checklist

- [x] Restored project and packaged acceptance verification
- [x] Standards, lint, Polylith and normal signed commit hooks
- [ ] Exact-head review settled and all CI green
